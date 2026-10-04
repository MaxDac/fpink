import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// ONNX Runtime built from source with telemetry compiled out (scripts/build-runtime.sh).
// The checked-in Java API sources and notices, and the native libraries the script writes to
// native/ (never committed), are that build, pinned in source-runtime.lock.json
// build.expectedSha256. Release builds use this module; debug builds use the Maven AAR because
// they also need x86_64 for emulator tests.
android {
    namespace = "com.fpink.recognition.onnxruntime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        checkNotNull(variant.sources.jniLibs) {
            "JNI library sources are unavailable for ${variant.name}"
        }.addStaticSourceDirectory("native")
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

val sourceRuntimeManifest = file("source-runtime.lock.json")
val sourceProvenance = file("build/source-output/PROVENANCE")
val sourceChecksums = file("build/source-output/SHA256SUMS")
// Set when scripts/build-runtime.sh rebuilt the runtime (for example in the F-Droid build
// step); Gradle then also verifies its provenance against the lock.
val sourceBuiltRuntime = providers.gradleProperty("ortRuntimeBuiltFromSource").isPresent
// Local experiments on other hosts/toolchains produce different runtime bytes.
val allowUnpinnedRuntime = providers.gradleProperty("allowUnpinnedOrtRuntime").isPresent

@Suppress("UNCHECKED_CAST")
val lock = JsonSlurper().parse(sourceRuntimeManifest) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val lockBuild = lock["build"] as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val javaSourcesDir = (lockBuild["javaSources"] as Map<String, Any>)["to"] as String
@Suppress("UNCHECKED_CAST")
val sourceRuntimeFiles = (lockBuild["outputs"] as List<String>).toSet() +
    ((lockBuild["notices"] as Map<String, Any>).let { notices ->
        (notices["from"] as List<String>).map { "${notices["to"]}/$it" }
    }) + javaSourcesDir
@Suppress("UNCHECKED_CAST")
val nativeLibraries = (lockBuild["outputs"] as List<String>).filter { it.startsWith("native/") }
@Suppress("UNCHECKED_CAST")
val pinnedSourceRuntime = lockBuild["expectedSha256"] as Map<String, String>?
check(pinnedSourceRuntime == null || pinnedSourceRuntime.keys == sourceRuntimeFiles) {
    "source-runtime.lock.json build.expectedSha256 must pin exactly $sourceRuntimeFiles."
}
// Debug builds use the Maven AAR; both build types must run the same ONNX Runtime release.
@Suppress("UNCHECKED_CAST")
val sourceTag = (lock["source"] as Map<String, Any>)["tag"] as String
check(sourceTag == "v${libs.versions.onnxruntime.get()}") {
    "source-runtime.lock.json source.tag $sourceTag does not match the onnxruntime version " +
        "${libs.versions.onnxruntime.get()} in gradle/libs.versions.toml."
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Same digest as build-runtime.sh: SHA-256 over "<sha256>  <relative path>\n" lines, by path.
fun treeSha256(directory: File): String = sha256(
    directory.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(directory).invariantSeparatorsPath to it }
        .sortedBy { it.first }
        .joinToString("") { (path, file) -> "${sha256(file)}  $path\n" }
        .toByteArray(),
)

fun artifactSha256(path: String): String? {
    val artifact = file(path)
    return when {
        path == javaSourcesDir -> if (artifact.isDirectory) treeSha256(artifact) else null
        artifact.isFile -> sha256(artifact)
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
fun verifySourceRuntimeProvenance() {
    val source = lock["source"] as Map<String, Any>
    check(sourceProvenance.isFile && sourceChecksums.isFile) {
        "Missing ${sourceProvenance.name}/${sourceChecksums.name}; run recognition/onnxruntime/scripts/build-runtime.sh first."
    }
    val provenance = sourceProvenance.readLines().filter { it.isNotBlank() }.associate {
        it.substringBefore('=') to it.substringAfter('=')
    }
    val expected = mapOf(
        "source" to source["repository"] as String,
        "commit" to source["commit"] as String,
        "ndk" to lockBuild["ndkRevision"] as String,
        "cmakeDefines" to (lockBuild["cmakeDefines"] as List<String>).joinToString(" "),
    )
    check(provenance == expected) {
        "ONNX Runtime source-runtime PROVENANCE $provenance does not match source-runtime.lock.json $expected."
    }
    val checksums = sourceChecksums.readLines().filter { it.isNotBlank() }.associate {
        it.substringAfter("  ").removePrefix("*") to it.substringBefore("  ")
    }
    check(checksums.keys == sourceRuntimeFiles) {
        "ONNX Runtime SHA256SUMS must list exactly $sourceRuntimeFiles, found ${checksums.keys}."
    }
    checksums.forEach { (path, hash) ->
        check(artifactSha256(path) == hash) {
            "Source-built ONNX Runtime artifact $path does not match build/source-output/SHA256SUMS."
        }
    }
    if (!allowUnpinnedRuntime) {
        check(checksums == pinnedSourceRuntime) {
            "The source-built ONNX Runtime is not the reproducible one pinned in source-runtime.lock.json " +
                "(build.expectedSha256 $pinnedSourceRuntime, built $checksums). Build it in the F-Droid buildserver " +
                "image (scripts/fdroid-rb-docker.sh), or pass -PallowUnpinnedOrtRuntime for a local experiment."
        }
    }
}

// Without -PortRuntimeBuiltFromSource: the checked-in Java API and notices must match the pin,
// and native libraries left in native/ by an earlier build must too. Missing libraries only
// fail when a release APK packages them (requireOrtNativeLibraries), so JVM tests still run.
fun verifyCheckedInRuntime() {
    checkNotNull(pinnedSourceRuntime) {
        "source-runtime.lock.json does not pin build.expectedSha256 yet; build the runtime with " +
            "scripts/build-runtime.sh and pass -PortRuntimeBuiltFromSource -PallowUnpinnedOrtRuntime."
    }.forEach { (path, hash) ->
        if (path in nativeLibraries && !file(path).exists()) return@forEach
        check(artifactSha256(path) == hash) {
            "ONNX Runtime artifact $path is not the source build pinned in source-runtime.lock.json " +
                "build.expectedSha256; restore it from git or rebuild it with scripts/fdroid-rb-docker.sh."
        }
    }
}

val verifyOrtRuntime = tasks.register("verifyOrtRuntime") {
    group = "verification"
    description = "Verify the source-built ONNX Runtime libraries, Java API and notices against source-runtime.lock.json."
    inputs.file(sourceRuntimeManifest)
    inputs.files(sourceRuntimeFiles.map(::file)).optional()
    if (sourceBuiltRuntime) {
        inputs.files(sourceProvenance, sourceChecksums)
    }
    doLast {
        if (sourceBuiltRuntime) verifySourceRuntimeProvenance() else verifyCheckedInRuntime()
    }
}
tasks.named("preBuild") { dependsOn(verifyOrtRuntime) }

val requireOrtNativeLibraries = tasks.register("requireOrtNativeLibraries") {
    group = "verification"
    description = "Fail when the source-built ONNX Runtime libraries are missing from native/."
    val libraries = nativeLibraries.map(::file)
    doLast {
        val missing = libraries.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "The source-built ONNX Runtime libraries ${missing.map { it.relativeTo(projectDir).invariantSeparatorsPath }} " +
                "are missing; they are built, not committed. Release APKs come from the F-Droid replay " +
                "(scripts/fdroid-rb-docker.sh), which runs recognition/onnxruntime/scripts/build-runtime.sh. " +
                "See recognition/onnxruntime/README.md."
        }
    }
}
// Every release task that hands the JNI libraries to a consumer (APK or AAR).
tasks.matching { it.name in setOf("mergeReleaseJniLibFolders", "copyReleaseJniLibsProjectOnly") }
    .configureEach { dependsOn(requireOrtNativeLibraries) }
