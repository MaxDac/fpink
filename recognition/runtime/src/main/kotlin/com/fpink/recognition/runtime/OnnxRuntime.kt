package com.fpink.recognition.runtime

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import com.fpink.core.ai.RecognitionError
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** A file packaged under `src/main/assets`, pinned by size and SHA-256. */
data class BundledAsset(val path: String, val bytes: Long, val sha256: String) {
    init {
        require(path.isNotBlank() && bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }

    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * Copies pinned assets out of the APK into `noBackupFilesDir` (so ONNX Runtime can memory-map a
 * real file) and verifies every byte. Nothing is ever downloaded.
 */
object BundledAssets {
    private const val ROOT = "recognition-models"
    private val LEGACY_DIRECTORIES = listOf("paddle-v5", "kraken-ppocrv6-medium")
    private val lock = Any()
    private val verified = mutableSetOf<String>()

    fun materialize(context: Context, asset: BundledAsset): File = synchronized(lock) {
        val noBackup = context.noBackupFilesDir
        LEGACY_DIRECTORIES.forEach { File(noBackup, it).deleteRecursively() }
        val directory = File(noBackup, "$ROOT/${asset.sha256.take(16)}")
        val target = File(directory, asset.fileName)
        if (target.absolutePath in verified && target.isFile && target.length() == asset.bytes) return target
        if (target.isFile && target.length() == asset.bytes && target.inputStream().use { sha256(it) } == asset.sha256) {
            verified += target.absolutePath
            return target
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the local model directory" }
        val staged = File(directory, "${asset.fileName}.pending")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            context.assets.open(asset.path).use { input ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        total += count
                    }
                    output.fd.sync()
                }
            }
            check(total == asset.bytes && digest.digest().hex() == asset.sha256) {
                "Bundled model asset verification failed: ${asset.fileName}"
            }
            if (target.exists()) check(target.delete()) { "Cannot replace a corrupt local model" }
            check(staged.renameTo(target)) { "Cannot publish the local model" }
            verified += target.absolutePath
        } finally {
            if (staged.exists()) staged.delete()
        }
        target
    }

    /** Reads a small pinned text asset after verifying it. */
    fun readText(context: Context, asset: BundledAsset): String {
        val bytes = context.assets.open(asset.path).use { it.readBytes() }
        check(bytes.size.toLong() == asset.bytes && sha256(bytes.inputStream()) == asset.sha256) {
            "Bundled model asset verification failed: ${asset.fileName}"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

/** Device checks shared by every bundled ONNX model. */
object OnnxRuntimeSupport {
    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    /** Throws [RecognitionError.UnsupportedDevice] when ONNX Runtime must not be loaded. */
    fun checkDevice(context: Context) {
        if (Build.SUPPORTED_ABIS.none { it in supportedAbis }) {
            throw RecognitionError.UnsupportedDevice(
                "On-device recognition requires an Android ABI supported by ONNX Runtime (${supportedAbis.joinToString()}).",
            )
        }
        if (runsUnderNativeTranslation(context)) {
            // ONNX Runtime crashes the process in its static initializers when loaded through
            // an ARM-to-x86 native bridge, so fail closed before touching it.
            throw RecognitionError.UnsupportedDevice(
                "On-device recognition cannot run through this device's ARM translation layer.",
            )
        }
    }

    /** True when the app's native libraries target a different ISA than the device's primary ABI. */
    private fun runsUnderNativeTranslation(context: Context): Boolean {
        val loadedIsa = context.applicationInfo.nativeLibraryDir?.let { File(it).name } ?: return false
        val primaryIsa = when (val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            else -> abi
        }
        return loadedIsa in setOf("arm64", "arm", "x86_64", "x86") && loadedIsa != primaryIsa
    }
}

/**
 * Lazily opened ONNX session for one pinned model file. [close] releases the native session;
 * the next [run] reopens it, so strategies can free memory between pages.
 */
class OnnxModelSession(context: Context, private val model: BundledAsset, private val label: String) : AutoCloseable {
    private val context = context.applicationContext
    private val lock = Any()
    private var session: OrtSession? = null

    val environment: OrtEnvironment get() = OrtEnvironment.getEnvironment()

    /** Verifies the device, the packaged bytes and that ONNX Runtime accepts the graph. */
    fun readiness(): Result<Unit> = recognitionResult(label) {
        synchronized(lock) {
            if (session == null) open().close()
        }
    }

    fun <T> run(block: (OrtEnvironment, OrtSession) -> T): T = synchronized(lock) {
        val active = session ?: open().also { session = it }
        block(environment, active)
    }

    override fun close() = synchronized(lock) {
        session?.close()
        session = null
    }

    private fun open(): OrtSession {
        OnnxRuntimeSupport.checkDevice(context)
        val file = BundledAssets.materialize(context, model)
        return environment.createSession(file.absolutePath, OrtSession.SessionOptions())
    }
}

/** Runs [block], mapping failures to the [RecognitionError] taxonomy. Cancellation propagates. */
inline fun <T> recognitionResult(label: String, block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: RecognitionError) {
    Result.failure(error)
} catch (error: UnsatisfiedLinkError) {
    Result.failure(RecognitionError.UnsupportedDevice("ONNX Runtime for $label cannot be loaded on this device."))
} catch (error: IllegalArgumentException) {
    Result.failure(RecognitionError.UnsupportedDevice(error.message ?: "$label input limit exceeded."))
} catch (error: OrtException) {
    Result.failure(RecognitionError.ModelUnavailable("Local $label inference failed.", error))
} catch (error: Exception) {
    Result.failure(RecognitionError.ModelUnavailable("Bundled $label model is missing, corrupt or failed.", error))
}
