import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fpink.recognition.paddle"
    compileSdk = 36
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
@Suppress("UNCHECKED_CAST")
val pinnedArtifacts = ((JsonSlurper().parse(artifactManifest) as Map<String, Any>)["archives"] as List<Map<String, Any>>)
    .flatMap { it["files"] as List<Map<String, Any>> }
    .map {
        val expected = (it["normalization"] as? Map<String, Any>) ?: it
        Triple(file(it["destination"] as String), (expected["bytes"] as Number).toLong(), expected["sha256"] as String)
    }

val licenseManifest = file("licenses.lock.json")
@Suppress("UNCHECKED_CAST")
val pinnedLicenses = (JsonSlurper().parse(licenseManifest) as List<Map<String, Any>>)
    .map { Triple(file("src/main/assets/paddle/licenses/${it["name"]}"), (it["bytes"] as Number).toLong(), it["sha256"] as String) }

val verifyPaddleArtifacts = tasks.register("verifyPaddleArtifacts") {
    group = "verification"
    description = "Verify bundled offline models, dictionary, native runtime and headers without downloading anything."
    inputs.file(artifactManifest)
    inputs.file(licenseManifest)
    inputs.files(pinnedArtifacts.map { it.first })
    inputs.files(pinnedLicenses.map { it.first })
    doLast {
        (pinnedArtifacts + pinnedLicenses).forEach { (artifact, bytes, expectedHash) ->
            check(artifact.isFile && artifact.length() == bytes) {
                "Missing or incorrect Paddle artifact ${artifact.name}; run recognition\\paddle\\scripts\\prepare.ps1."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) {
                "Pinned Paddle SHA-256 mismatch: ${artifact.name}"
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyPaddleArtifacts) }
