# ONNX Runtime's AAR only ships keep rules for its telemetry classes. Its JNI layer looks up the
# Java API (OrtEnvironment, OnnxTensor, OrtException, ...) by name and signature.
-keep class ai.onnxruntime.** { *; }

# ServiceLoader seams (see core/ai RecognitionProviderPlugin and app SettingsExtension). The
# private `full` overlay registers its implementations the same way, so these rules cover it too.
-keep interface com.fpink.core.ai.RecognitionProviderPlugin
-keep class * implements com.fpink.core.ai.RecognitionProviderPlugin { public <init>(); }
-keep interface com.fpink.capture.ui.settings.SettingsExtension
-keep class * implements com.fpink.capture.ui.settings.SettingsExtension { public <init>(); }
