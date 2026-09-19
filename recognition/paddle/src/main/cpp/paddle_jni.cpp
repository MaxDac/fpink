#include <jni.h>

#include <algorithm>
#include <cmath>
#include <fstream>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "ocr_geometry.h"
#include "paddle_api.h"

namespace {
struct Cancelled {};
using CheckCancellation = std::function<void()>;
using Predictor = std::shared_ptr<paddle::lite_api::PaddlePredictor>;
struct Line {
    std::string text;
    fpink::Quad polygon;
    float confidence;
};

std::string utf8(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing model path");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read model path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

Predictor predictor(const std::string& path) {
    paddle::lite_api::MobileConfig config;
    config.set_model_from_file(path);
    config.set_threads(2);
    config.set_power_mode(paddle::lite_api::LITE_POWER_NO_BIND);
    auto result = paddle::lite_api::CreatePaddlePredictor(config);
    if (!result) throw std::runtime_error("Cannot create Paddle predictor");
    if (result->GetVersion().find("v2.14") == std::string::npos)
        throw std::runtime_error("Unexpected Paddle runtime version");
    return result;
}

std::vector<std::string> load_dictionary(const std::string& path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("Cannot open Paddle dictionary");
    std::vector<std::string> result;
    std::string line;
    while (std::getline(input, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) throw std::runtime_error("Invalid Paddle dictionary entry");
        result.push_back(line);
    }
    if (result.size() != 18383) throw std::runtime_error("Unexpected Paddle dictionary size");
    return result;
}

std::vector<fpink::Box> detect(Predictor& detector, const std::vector<int32_t>& pixels,
                               int width, int height, const CheckCancellation& check) {
    const auto [dw, dh] = fpink::detector_size(width, height);
    auto input = detector->GetInput(0);
    input->Resize({1, 3, dh, dw});
    float* tensor = input->mutable_data<float>();
    const float mean[] = {0.485f, 0.456f, 0.406f};
    const float stddev[] = {0.229f, 0.224f, 0.225f};
    for (int y = 0; y < dh; ++y) {
        if (y % 32 == 0) check();
        for (int x = 0; x < dw; ++x) {
            const auto bgr = fpink::sample_bgr(pixels, width, height,
                (x + 0.5f) * width / dw - 0.5f, (y + 0.5f) * height / dh - 0.5f);
            for (int c = 0; c < 3; ++c)
                tensor[c * dw * dh + y * dw + x] = (bgr[c] / 255.f - mean[c]) / stddev[c];
        }
    }
    check();
    detector->Run();
    check();
    const auto output = detector->GetOutput(0);
    const auto shape = output->shape();
    if (shape.size() != 4 || shape[0] != 1 || shape[1] != 1 ||
        shape[2] < 1 || shape[2] > 1024 || shape[3] < 1 || shape[3] > 1024)
        throw std::runtime_error("Unexpected Paddle detector output");
    auto boxes = fpink::boxes_from_map(output->data<float>(), static_cast<int>(shape[3]), static_cast<int>(shape[2]));
    check();
    // Normalize using the OUTPUT map dimensions, not the resized input or the original image.
    // The resulting dimensionless vertices address precisely the original prepared image.
    for (auto& box : boxes)
        box.points = fpink::normalized(box.points, static_cast<int>(shape[3]), static_cast<int>(shape[2]));
    return boxes;
}

std::pair<std::string, float> recognize_line(
    Predictor& recognizer, const fpink::Quad& normalized,
    const std::vector<int32_t>& pixels, int width, int height,
    const std::vector<std::string>& dictionary, const CheckCancellation& check) {
    fpink::Quad quad = normalized;
    for (auto& p : quad) { p.x *= width; p.y *= height; }
    auto length = [](fpink::Point a, fpink::Point b) { return std::hypot(a.x - b.x, a.y - b.y); };
    float crop_width = std::max(length(quad[0], quad[1]), length(quad[3], quad[2]));
    float crop_height = std::max(length(quad[0], quad[3]), length(quad[1], quad[2]));
    if (crop_width < 1 || crop_height < 1) return {"", 0.f};
    // Same tall-line convention as Paddle's rotate-crop utility; no orientation classifier is bundled.
    if (crop_height >= crop_width * 1.5f) {
        std::rotate(quad.begin(), quad.begin() + 1, quad.end());
        std::swap(crop_width, crop_height);
    }
    constexpr int rh = 48;
    const int resized_width = std::max(1, static_cast<int>(std::ceil(rh * crop_width / crop_height)));
    if (resized_width > 2048) throw std::invalid_argument("Text line exceeds the offline width limit");
    const int rw = std::max(320, resized_width);
    auto input = recognizer->GetInput(0);
    input->Resize({1, 3, rh, rw});
    float* tensor = input->mutable_data<float>();
    std::fill(tensor, tensor + 3 * rh * rw, 0.f);
    // A resized detector rectangle maps to an affine quadrilateral in the original image.
    // Bilinear quad interpolation also preserves clipped edge vertices.
    for (int y = 0; y < rh; ++y) {
        check();
        const float v = (y + 0.5f) / rh;
        for (int x = 0; x < resized_width; ++x) {
            const float u = (x + 0.5f) / resized_width;
            const float sx = (1 - v) * ((1 - u) * quad[0].x + u * quad[1].x) +
                             v * ((1 - u) * quad[3].x + u * quad[2].x);
            const float sy = (1 - v) * ((1 - u) * quad[0].y + u * quad[1].y) +
                             v * ((1 - u) * quad[3].y + u * quad[2].y);
            const auto bgr = fpink::sample_bgr(pixels, width, height, sx, sy);
            for (int c = 0; c < 3; ++c) tensor[c * rh * rw + y * rw + x] = bgr[c] / 127.5f - 1.f;
        }
    }
    check();
    recognizer->Run();
    check();
    const auto output = recognizer->GetOutput(0);
    const auto shape = output->shape();
    if (shape.size() != 3 || shape[0] != 1 || shape[1] < 1 || shape[1] > 1024 ||
        shape[2] != static_cast<int64_t>(dictionary.size() + 2))
        throw std::runtime_error("Unexpected Paddle recognizer output");
    return fpink::decode_ctc(output->data<float>(), static_cast<int>(shape[1]), static_cast<int>(shape[2]), dictionary);
}

void throw_java(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto clazz = env->FindClass(type);
    if (clazz) env->ThrowNew(clazz, message);
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_fpink_recognition_paddle_NativeBridge_abi(JNIEnv* env, jobject) {
    return env->NewStringUTF("arm64-v8a");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_fpink_recognition_paddle_NativeBridge_recognize(
    JNIEnv* env, jobject, jstring detector_path, jstring recognizer_path, jstring dictionary_path,
    jintArray argb, jint width, jint height, jobject cancellation) {
    try {
        if (!argb || !cancellation || width < 16 || height < 16 || width > 8192 || height > 8192 ||
            static_cast<int64_t>(width) * height > 16000000 ||
            env->GetArrayLength(argb) != static_cast<int64_t>(width) * height)
            throw std::invalid_argument("Invalid prepared image buffer");
        const auto cancellation_class = env->GetObjectClass(cancellation);
        const auto cancelled_method = env->GetMethodID(cancellation_class, "isCancelled", "()Z");
        if (!cancelled_method) throw std::runtime_error("Cannot create native cancellation callback");
        const CheckCancellation check = [&]() {
            if (env->ExceptionCheck() || env->CallBooleanMethod(cancellation, cancelled_method)) throw Cancelled{};
        };
        check();
        std::vector<int32_t> pixels(static_cast<size_t>(width) * height);
        env->GetIntArrayRegion(argb, 0, static_cast<jsize>(pixels.size()), pixels.data());
        if (env->ExceptionCheck()) return nullptr;
        const auto dictionary = load_dictionary(utf8(env, dictionary_path));
        check();
        auto detector = predictor(utf8(env, detector_path));
        check();
        const auto boxes = detect(detector, pixels, width, height, check);
        // Release detector tensors before allocating the recognizer's much larger CTC output.
        detector.reset();
        check();
        std::vector<Line> lines;
        if (!boxes.empty()) {
            auto recognizer = predictor(utf8(env, recognizer_path));
            for (const auto& box : boxes) {
                check();
                const auto [text, score] = recognize_line(recognizer, box.points, pixels, width, height, dictionary, check);
                if (!text.empty() && score >= 0.5f) lines.push_back({text, box.points, score});
            }
        }
        check();
        const auto line_class = env->FindClass("com/fpink/recognition/paddle/NativeLine");
        if (!line_class) return nullptr;
        const auto constructor = env->GetMethodID(line_class, "<init>", "([B[FF)V");
        if (!constructor) return nullptr;
        const auto result = env->NewObjectArray(static_cast<jsize>(lines.size()), line_class, nullptr);
        if (!result) return nullptr;
        for (size_t i = 0; i < lines.size(); ++i) {
            check();
            const auto& line = lines[i];
            const auto text = env->NewByteArray(static_cast<jsize>(line.text.size()));
            const auto polygon = env->NewFloatArray(8);
            if (!text || !polygon) return nullptr;
            env->SetByteArrayRegion(text, 0, static_cast<jsize>(line.text.size()),
                                   reinterpret_cast<const jbyte*>(line.text.data()));
            jfloat coordinates[8];
            for (int j = 0; j < 4; ++j) {
                coordinates[2 * j] = line.polygon[j].x;
                coordinates[2 * j + 1] = line.polygon[j].y;
            }
            env->SetFloatArrayRegion(polygon, 0, 8, coordinates);
            const auto object = env->NewObject(line_class, constructor, text, polygon, line.confidence);
            if (!object || env->ExceptionCheck()) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(i), object);
            env->DeleteLocalRef(object);
            env->DeleteLocalRef(text);
            env->DeleteLocalRef(polygon);
        }
        return result;
    } catch (const Cancelled&) {
        throw_java(env, "java/util/concurrent/CancellationException", "Paddle recognition cancelled");
    } catch (const std::invalid_argument& error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception&) {
        throw_java(env, "java/lang/IllegalStateException", "Paddle model execution failed");
    }
    return nullptr;
}
