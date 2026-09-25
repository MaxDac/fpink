import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val baseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull
require((releaseVersionName == null) == (releaseVersionCode == null)) {
    "Set both -PreleaseVersionName and -PreleaseVersionCode, or neither."
}
require(!providers.gradleProperty("requireReleaseVersion").isPresent || releaseVersionName != null) {
    "Publishing requires explicit -PreleaseVersionName and -PreleaseVersionCode."
}
val resolvedVersionName = requireNotNull(releaseVersionName ?: baseVersion.getProperty("versionName")) {
    "version.properties must define versionName."
}
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?").matches(resolvedVersionName)) {
    "versionName must be X.Y.Z or X.Y.Z-prerelease, without leading zeros or build metadata."
}
require(resolvedVersionName.substringAfter('-', "").split('.').none {
    it.matches(Regex("[0-9]+")) && it.length > 1 && it.startsWith("0")
}) {
    "Numeric prerelease identifiers cannot have leading zeros."
}
val versionCodeText = releaseVersionCode ?: baseVersion.getProperty("versionCode")
val resolvedVersionCode = versionCodeText?.toIntOrNull()
require(versionCodeText != null && Regex("[1-9][0-9]*").matches(versionCodeText) &&
    resolvedVersionCode != null && resolvedVersionCode in 1..2_100_000_000) {
    "versionCode must be an integer between 1 and 2100000000."
}
// Opt-in, local-only: instrument the minified release APK instead of the debug one.
val instrumentReleaseBuild = providers.gradleProperty("instrumentReleaseBuild").isPresent
require(!(instrumentReleaseBuild && releaseVersionName != null)) {
    "-PinstrumentReleaseBuild is for local validation only and cannot be combined with a publishing version."
}

android {
    namespace = "com.fpink.capture"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fpink.capture"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "com.fpink.capture.acceptance.AcceptanceTestRunner"
        // Paddle Lite ships arm64-v8a only; one ABI keeps every OCR provider available on every
        // device the APK installs on and stops ONNX Runtime from bloating the APK with other ABIs.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (instrumentReleaseBuild) {
                // Local validation only (see docs/FDROID_VALIDATION.md): lets the acceptance
                // suite install and instrument the minified release APK. Never used for publishing.
                signingConfig = signingConfigs.getByName("debug")
                proguardFiles("proguard-release-instrumentation.pro")
                testProguardFiles("proguard-release-instrumentation-test.pro")
            }
        }
    }
    if (instrumentReleaseBuild) {
        testBuildType = "release"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Public/F-Droid default: bundled Paddle OCR only, no proprietary code or binaries.
        create("foss") {
            dimension = "distribution"
        }
        // Private-companion-repo build only: adds Azure/MyScript recognition providers via
        // a private submodule. Degrades to an exact copy of `foss` when that submodule is
        // absent, which is always true for public clones, CI, and F-Droid.
        create("full") {
            dimension = "distribution"
        }
    }

    packaging {
        // Preserve the reviewed runtime bytes for APK provenance verification.
        jniLibs.keepDebugSymbols += "**/libpaddle_light_api_shared.so"
    }

    // Reproducible builds: F-Droid compares its rebuild with our signed release. This block is
    // only added when AGP signs (we sign externally), but keep it out explicitly.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Only wires in real content when the private submodule is present; otherwise `full`
    // builds identically to `foss`. Never present in public clones, CI, or F-Droid.
    val privateOverlayKotlin = rootDir.resolve("private/app-overlay/kotlin")
    val privateOverlayResources = rootDir.resolve("private/app-overlay/resources")
    if (privateOverlayKotlin.isDirectory) {
        sourceSets.getByName("full") {
            kotlin.srcDir(privateOverlayKotlin)
            if (privateOverlayResources.isDirectory) {
                resources.srcDir(privateOverlayResources)
            }
        }
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
    implementation(project(":recognition:kraken"))

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
    implementation(libs.kotlinx.serialization.json)

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
    if (instrumentReleaseBuild) {
        releaseImplementation(libs.compose.ui.test.manifest)
    }
}

// Only added when the private submodule's recognition modules are actually present, so
// public clones/CI/F-Droid never resolve a dependency on them.
listOf("azure", "myscript").forEach { name ->
    if (project.findProject(":recognition:$name") != null) {
        dependencies.add("fullImplementation", project(":recognition:$name"))
    }
}
// The app-overlay's Azure "test connection" action builds an HttpClient directly (mirroring
// AzureReadProvider's own construction), so it needs Ktor's OkHttp engine on its own compile
// classpath too; :recognition:azure only depends on it as `implementation`, not `api`.
if (project.findProject(":recognition:azure") != null) {
    dependencies.add("fullImplementation", libs.ktor.client.core)
    dependencies.add("fullImplementation", libs.ktor.client.okhttp)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

androidComponents {
    onVariants { variant ->
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
                    "assets/kraken/NOTICE.txt",
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
                        val unexpectedAbis = archive.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("lib/") }
                            .map { it.removePrefix("lib/").substringBefore('/') }
                            .filter { it != "arm64-v8a" }
                            .toSortedSet()
                        check(unexpectedAbis.isEmpty()) {
                            "${apk.name} must ship native libraries for arm64-v8a only, but also contains lib/$unexpectedAbis"
                        }
                    }
                }
            }
        }
        tasks.named("check") { dependsOn(verifyPackage) }

        if (variant.flavorName == "foss") {
            val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            val variantName = variant.name
            val verifyOffline = tasks.register("verify${variantName.replaceFirstChar { it.uppercase() }}OfflineManifest") {
                group = "verification"
                description = "Check that the public build requests no network permissions."
                inputs.file(mergedManifest)
                doLast {
                    val manifest = mergedManifest.get().asFile.readText()
                    val forbidden = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
                        .filter { manifest.contains("\"$it\"") }
                    check(forbidden.isEmpty()) {
                        "The $variantName merged manifest must stay offline but requests $forbidden"
                    }
                    check(!manifest.contains("ai.onnxruntime.TelemetryInitializer")) {
                        "The $variantName merged manifest must not register ONNX Runtime's telemetry initializer"
                    }
                }
            }
            tasks.named("check") { dependsOn(verifyOffline) }
        }
    }
}
