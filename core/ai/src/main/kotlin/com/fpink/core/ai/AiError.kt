package com.fpink.core.ai

sealed class AiError(override val message: String, override val cause: Throwable? = null) : Exception(message, cause) {
    class NetworkError(message: String, cause: Throwable? = null) : AiError(message, cause)
    class AuthError(message: String) : AiError(message)
    class RateLimited(message: String) : AiError(message)
    class MalformedResponse(message: String, cause: Throwable? = null) : AiError(message, cause)
    class Unknown(message: String, cause: Throwable? = null) : AiError(message, cause)
}
