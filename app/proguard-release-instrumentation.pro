# Only applied with -PinstrumentReleaseBuild (local validation of the minified release APK).
# AGP strips from the test APK every class the app already provides, and the acceptance suite
# calls app internals and libraries (Compose test, AndroidX Test, coroutines, ...) directly, so
# those must survive app shrinking. This masks shrinking of FPInk's own classes, which is why the
# exact shipped APK is additionally smoke-tested without these rules (docs/FDROID_VALIDATION.md).
# Native libraries, assets, resources shrinking and the rules in proguard-rules.pro still apply.
-keep class com.fpink.** { *; }
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.jetbrains.** { *; }
-keep class com.google.common.** { *; }
