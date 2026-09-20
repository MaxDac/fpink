import com.android.build.api.artifact.SingleArtifact
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.fpink.capture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fpink.capture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.fpink.capture.acceptance.AcceptanceTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ai"))
    implementation(project(":core:storage"))
    implementation(project(":recognition:paddle"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.coil.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Compose's older transitive Espresso uses InputManager APIs removed on new devices.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val verifyPackage = tasks.register("verify${variant.name.replaceFirstChar { it.uppercase() }}RecognitionPackage") {
            group = "verification"
            description = "Check that the installable APK contains the offline models and every required OCR library."
            inputs.dir(apkDirectory)
            doLast {
                val apks = apkDirectory.get().asFile.listFiles()?.filter { it.extension == "apk" }.orEmpty()
                check(apks.isNotEmpty()) { "No APK found for offline recognition package verification" }
                val requiredEntries = listOf(
                    "assets/paddle/PP-OCRv5_mobile_det.nb",
                    "assets/paddle/PP-OCRv5_mobile_rec.nb",
                    "assets/paddle/ppocr_keys_ocrv5.txt",
                    "assets/paddle/NOTICE.txt",
                    "lib/arm64-v8a/libfpink_paddle.so",
                    "lib/arm64-v8a/libpaddle_light_api_shared.so",
                    "lib/arm64-v8a/libc++_shared.so",
                )
                apks.forEach { apk ->
                    ZipFile(apk).use { archive ->
                        requiredEntries.forEach { name ->
                            check((archive.getEntry(name)?.size ?: 0L) > 0L) {
                                "${apk.name} is missing the required offline recognition artifact $name"
                            }
                        }
                    }
                }
            }
        }
        tasks.named("check") { dependsOn(verifyPackage) }
    }
}
