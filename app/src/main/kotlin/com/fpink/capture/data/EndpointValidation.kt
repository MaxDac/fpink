package com.fpink.capture.data

import java.net.URI
import java.util.Locale

private val azureResourceHost = Regex(
    "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\." +
        "(?:cognitiveservices\\.azure\\.(?:com|us|cn)|" +
        "api\\.cognitive\\.microsoft\\.(?:com|us)|api\\.cognitive\\.azure\\.cn)",
)

internal fun isValidAzureEndpoint(value: String): Boolean = try {
    val uri = URI(value.trim())
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host?.lowercase(Locale.ROOT)?.matches(azureResourceHost) == true &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && (uri.port == -1 || uri.port == 443)
} catch (_: Exception) {
    false
}
