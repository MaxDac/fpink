import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fpink.recognition.kraken"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.onnxruntime.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

@Suppress("UNCHECKED_CAST")
val manifest = JsonSlurper().parse(file("artifacts.lock.json")) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val pinnedAssets = (manifest["packagedFiles"] as List<Map<String, Any>>).map {
    Triple(file(it["destination"] as String), (it["bytes"] as Number).toLong(), it["sha256"] as String)
}

val verifyKrakenArtifacts = tasks.register("verifyKrakenArtifacts") {
    group = "verification"
    description = "Verify packaged Kraken notices/export metadata without downloading anything."
    inputs.file("artifacts.lock.json")
    inputs.files(pinnedAssets.map { it.first })
    doLast {
        pinnedAssets.forEach { (artifact, bytes, expectedHash) ->
            check(artifact.isFile && artifact.length() == bytes) {
                "Missing or incorrect Kraken artifact ${artifact.name}; see recognition\\kraken\\README.md."
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
                "Pinned Kraken SHA-256 mismatch: ${artifact.name}"
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyKrakenArtifacts) }
tasks.withType<Test> { useJUnitPlatform() }
