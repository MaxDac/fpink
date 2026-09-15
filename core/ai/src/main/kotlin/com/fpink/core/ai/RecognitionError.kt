package com.fpink.core.ai

sealed class RecognitionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Configuration(message: String) : RecognitionError(message)
    class UnsupportedDevice(message: String) : RecognitionError(message)
    class ModelUnavailable(message: String, cause: Throwable? = null) : RecognitionError(message, cause)
    class Network(message: String, cause: Throwable? = null) : RecognitionError(message, cause)
    class Authentication(message: String) : RecognitionError(message)
    class RateLimited(message: String) : RecognitionError(message)
    class MalformedResponse(message: String, cause: Throwable? = null) : RecognitionError(message, cause)
}
