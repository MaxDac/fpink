# ONNX Runtime's AAR only ships keep rules for its telemetry classes. Its JNI layer looks up the
# Java API (OrtEnvironment, OnnxTensor, OrtException, ...) by name and signature.
-keep class ai.onnxruntime.** { *; }

# ServiceLoader seams (see core/ai RecognitionProviderPlugin and app SettingsExtension). The
# private `full` overlay registers its implementations the same way, so these rules cover it too.
-keep interface com.fpink.core.ai.RecognitionProviderPlugin
-keep class * implements com.fpink.core.ai.RecognitionProviderPlugin { public <init>(); }
-keep interface com.fpink.capture.ui.settings.SettingsExtension
-keep class * implements com.fpink.capture.ui.settings.SettingsExtension { public <init>(); }

# Reproducible builds: R8 rewrites ServiceLoader lookups for these coroutines services, and its
# output order has been non-deterministic (https://f-droid.org/docs/Reproducible_Builds/).
-keep class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.internal.MainDispatcherFactory
