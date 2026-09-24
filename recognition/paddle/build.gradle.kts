import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fpink.recognition.paddle"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources { noCompress += "nb" }
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

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

val artifactManifest = file("artifacts.lock.json")
val sourceRuntimeManifest = file("source-runtime.lock.json")
val sourceProvenance = file("build/source-output/PROVENANCE")
val sourceChecksums = file("build/source-output/SHA256SUMS")
val sourceBuildEnabled = providers.gradleProperty("buildPaddleRuntimeFromSource").isPresent
// Set when the runtime was already built by scripts/build-runtime.sh outside Gradle
// (for example in an F-Droid build step); Gradle then only verifies its provenance.
val sourceBuiltRuntime = providers.gradleProperty("paddleRuntimeBuiltFromSource").isPresent
check(!(sourceBuildEnabled && sourceBuiltRuntime)) {
    "Use either -PbuildPaddleRuntimeFromSource or -PpaddleRuntimeBuiltFromSource, not both."
}
val sourceRuntimeFiles = setOf(
    "native/arm64-v8a/libpaddle_light_api_shared.so",
    "src/main/cpp/third_party/paddle_lite/paddle_api.h",
    "src/main/cpp/third_party/paddle_lite/paddle_place.h",
)
@Suppress("UNCHECKED_CAST")
val pinnedArtifacts = ((JsonSlurper().parse(artifactManifest) as Map<String, Any>)["archives"] as List<Map<String, Any>>)
    .flatMap { it["files"] as List<Map<String, Any>> }
    .filter { !(sourceBuildEnabled || sourceBuiltRuntime) || (it["destination"] as String) !in sourceRuntimeFiles }
    .map {
        val expected = (it["normalization"] as? Map<String, Any>) ?: it
        Triple(file(it["destination"] as String), (expected["bytes"] as Number).toLong(), expected["sha256"] as String)
    }

val licenseManifest = file("licenses.lock.json")
@Suppress("UNCHECKED_CAST")
val pinnedLicenses = (JsonSlurper().parse(licenseManifest) as List<Map<String, Any>>)
    .map { Triple(file("src/main/assets/paddle/licenses/${it["name"]}"), (it["bytes"] as Number).toLong(), it["sha256"] as String) }

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

@Suppress("UNCHECKED_CAST")
fun verifySourceRuntimeProvenance() {
    val lock = JsonSlurper().parse(sourceRuntimeManifest) as Map<String, Any>
    val source = lock["source"] as Map<String, Any>
    val build = lock["build"] as Map<String, Any>
    check(sourceProvenance.isFile && sourceChecksums.isFile) {
        "Missing ${sourceProvenance.name}/${sourceChecksums.name}; run recognition/paddle/scripts/build-runtime.sh first."
    }
    val provenance = sourceProvenance.readLines().filter { it.isNotBlank() }.associate {
        it.substringBefore('=') to it.substringAfter('=')
    }
    val expected = mapOf(
        "source" to source["repository"] as String,
        "commit" to source["commit"] as String,
        "ndk" to build["ndkRevision"] as String,
        "arguments" to (build["arguments"] as List<String>).joinToString(" "),
    )
    check(provenance == expected) {
        "Paddle source-runtime PROVENANCE $provenance does not match source-runtime.lock.json $expected."
    }
    val checksums = sourceChecksums.readLines().filter { it.isNotBlank() }.associate {
        it.substringAfter("  ").removePrefix("*") to it.substringBefore("  ")
    }
    check(checksums.keys == sourceRuntimeFiles) {
        "Paddle source-runtime SHA256SUMS must list exactly $sourceRuntimeFiles, found ${checksums.keys}."
    }
    checksums.forEach { (path, hash) ->
        val artifact = file(path)
        check(artifact.isFile && sha256(artifact) == hash) {
            "Source-built Paddle artifact $path does not match build/source-output/SHA256SUMS."
        }
    }
}

val verifyPaddleArtifacts = tasks.register("verifyPaddleArtifacts") {
    group = "verification"
    description = "Verify bundled offline models, dictionary, native runtime and headers without downloading anything."
    inputs.file(artifactManifest)
    inputs.file(licenseManifest)
    inputs.files(pinnedArtifacts.map { it.first })
    inputs.files(pinnedLicenses.map { it.first })
    if (sourceBuildEnabled || sourceBuiltRuntime) {
        inputs.file(sourceRuntimeManifest)
        inputs.files(sourceRuntimeFiles.map(::file))
        inputs.files(sourceProvenance, sourceChecksums)
    }
    doLast {
        (pinnedArtifacts + pinnedLicenses).forEach { (artifact, bytes, expectedHash) ->
            check(artifact.isFile && artifact.length() == bytes) {
                "Missing or incorrect Paddle artifact ${artifact.name}; run recognition\\paddle\\scripts\\prepare.ps1."
            }
            check(sha256(artifact) == expectedHash) {
                "Pinned Paddle SHA-256 mismatch: ${artifact.name}"
            }
        }
        if (sourceBuildEnabled || sourceBuiltRuntime) verifySourceRuntimeProvenance()
    }
}
if (sourceBuildEnabled) {
    tasks.register("buildPaddleRuntimeFromSource") {
        group = "build"
        description = "Build the pinned Paddle Lite ARM64 runtime from source."
        inputs.file("source-runtime.lock.json")
        outputs.files(
            file("native/arm64-v8a/libpaddle_light_api_shared.so"),
            file("src/main/cpp/third_party/paddle_lite/paddle_api.h"),
            file("src/main/cpp/third_party/paddle_lite/paddle_place.h"),
        )
        outputs.upToDateWhen { false }
        doLast {
            val script = file("scripts/build-runtime.sh")
            val process = ProcessBuilder("bash", script.absolutePath)
                .directory(projectDir)
                .inheritIO()
                .start()
            check(process.waitFor() == 0) { "Paddle Lite source build failed." }
        }
    }
    tasks.named("verifyPaddleArtifacts") { dependsOn("buildPaddleRuntimeFromSource") }
    tasks.named("preBuild") { dependsOn("buildPaddleRuntimeFromSource") }
}
tasks.named("preBuild") { dependsOn(verifyPaddleArtifacts) }
