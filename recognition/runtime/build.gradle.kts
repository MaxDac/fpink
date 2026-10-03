import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fpink.recognition.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    api(project(":core:ai"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    // Release ships ONNX Runtime built from source without telemetry (recognition/onnxruntime);
    // debug keeps the upstream AAR because emulator tests also need its x86_64 libraries.
    debugApi(libs.onnxruntime.android)
    releaseApi(project(":recognition:onnxruntime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> { useJUnitPlatform() }