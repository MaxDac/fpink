# Only applied with -PinstrumentReleaseBuild: shrinks the acceptance test APK itself.
-dontobfuscate
-keep class com.fpink.capture.acceptance.** { *; }
-keep class com.fpink.capture.nativeacceptance.** { *; }
-dontwarn javax.lang.model.element.Modifier
