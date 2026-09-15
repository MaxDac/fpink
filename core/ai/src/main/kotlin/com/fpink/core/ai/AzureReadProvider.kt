package com.fpink.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Document Intelligence Read only: language auto-detection, without paid analysis add-ons.
 * The supplied client's engine is reused, but its logging, retry and redirect plugins are not.
 * The caller owns the supplied client and must keep it open for the duration of each operation.
 */
class AzureReadProvider(
    private val httpClient: HttpClient,
    private val config: AzureReadConfig,
) : RecognitionProvider {
    override suspend fun recognize(image: PreparedImage): Result<RecognitionDocument> = captureFailure {
        val endpoint = validatedEndpoint()
        withTimeoutOrNull(ANALYSIS_TIMEOUT_MILLIS) {
            isolatedClient().use { client ->
                val submission = client.post(
                    "$endpoint$MODEL_PATH:analyze?api-version=$API_VERSION&stringIndexType=utf16CodeUnit",
                ) {
                    header(SUBSCRIPTION_KEY_HEADER, config.apiKey)
                    contentType(ContentType.parse(image.mimeType))
                    setBody(image.bytes)
                }
                requireStatus(submission, 202)
                val operationUrl = validatedOperationUrl(submission, endpoint)
                poll(client, operationUrl, retryDelayMillis(submission.headers[HttpHeaders.RetryAfter]))
            }
        } ?: throw RecognitionError.Network("Azure Read timed out. Please try again later.")
    }

    /**
     * Reads prebuilt-read model metadata without uploading an image or submitting analysis.
     * Success verifies HTTPS connectivity, key authentication and model-read access only.
     * It does not prove permission to analyze images, available analysis quota, or OCR quality.
     */
    suspend fun testConnection(): Result<Unit> = captureFailure {
        val endpoint = validatedEndpoint()
        withTimeoutOrNull(CONNECTION_TIMEOUT_MILLIS) {
            isolatedClient().use { client ->
                val response = client.get("$endpoint$MODEL_PATH?api-version=$API_VERSION") {
                    header(SUBSCRIPTION_KEY_HEADER, config.apiKey)
                }
                requireStatus(response, 200)
                AzureReadResponseParser.validateModel(response.bodyAsText())
            }
        } ?: throw RecognitionError.Network("The Azure connection test timed out. Please try again.")
    }

    private fun isolatedClient(): HttpClient = HttpClient(httpClient.engine) {
        followRedirects = false
        expectSuccess = false
    }

    private suspend fun poll(
        client: HttpClient,
        operationUrl: String,
        initialDelayMillis: Long,
    ): RecognitionDocument {
        var nextDelay = initialDelayMillis
        var transientFailures = 0
        repeat(MAX_POLLS) {
            delay(nextDelay)
            currentCoroutineContext().ensureActive()
            val response = client.get(operationUrl) {
                header(SUBSCRIPTION_KEY_HEADER, config.apiKey)
            }
            nextDelay = retryDelayMillis(response.headers[HttpHeaders.RetryAfter])
            if (response.status.value in TRANSIENT_POLL_STATUSES) {
                transientFailures++
                if (transientFailures <= MAX_TRANSIENT_RETRIES) return@repeat
            }
            requireStatus(response, 200)
            transientFailures = 0
            val operation = AzureReadResponseParser.parseOperation(response.bodyAsText())
            when (operation.status) {
                "notStarted", "running" -> Unit
                "succeeded" -> {
                    val document = AzureReadResponseParser.normalize(
                        operation.analyzeResult
                            ?: throw RecognitionError.MalformedResponse("Azure Read returned no analysis result."),
                    )
                    currentCoroutineContext().ensureActive()
                    return document
                }
                "failed" -> throw serviceFailure(operation.error)
                "canceled" -> throw RecognitionError.Network("Azure canceled the recognition operation. Please retry.")
                else -> throw RecognitionError.MalformedResponse("Azure Read returned an unknown operation status.")
            }
        }
        throw RecognitionError.Network("Azure Read did not finish within the polling limit. Please try again later.")
    }

    private fun validatedEndpoint(): String {
        if (config.apiKey.isBlank() || config.apiKey.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) {
            throw RecognitionError.Configuration("Enter a valid Azure Document Intelligence API key.")
        }
        val uri = parseUri(config.endpoint.trim())
            ?: throw RecognitionError.Configuration(ENDPOINT_HELP)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            host == null || !AZURE_RESOURCE_HOST.matches(host) ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in setOf(-1, 443) || uri.rawPath !in setOf("", "/")
        ) {
            throw RecognitionError.Configuration(ENDPOINT_HELP)
        }
        return "https://$host"
    }

    private fun validatedOperationUrl(response: HttpResponse, endpoint: String): String {
        val locations = response.headers.getAll("Operation-Location")
        val value = locations?.singleOrNull()
            ?: throw RecognitionError.MalformedResponse("Azure Read did not return one valid polling location.")
        val uri = parseUri(value)
        val trusted = URI(endpoint)
        if (
            uri == null || !uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(trusted.host, ignoreCase = true) || uri.port !in setOf(-1, 443) ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            !OPERATION_PATH.matches(uri.rawPath.orEmpty()) ||
            uri.rawQuery != "api-version=$API_VERSION"
        ) {
            throw RecognitionError.MalformedResponse("Azure Read returned an untrusted or invalid polling location.")
        }
        return uri.toASCIIString()
    }

    private fun requireStatus(response: HttpResponse, expected: Int) {
        val status = response.status.value
        if (status == expected) return
        throw when (status) {
            401, 403 -> RecognitionError.Authentication(
                "Azure rejected access. Check the Document Intelligence endpoint, key and resource permissions.",
            )
            429 -> RecognitionError.RateLimited("Azure Read is rate limited. Please try again later.")
            400, 413, 415, 422 -> RecognitionError.Configuration(
                "Azure could not accept this image. Check the resource configuration or choose another image.",
            )
            404 -> RecognitionError.Configuration(
                "Azure Read was not found. Check the Document Intelligence resource endpoint and availability.",
            )
            408, in 500..599 -> RecognitionError.Network("Azure Read is temporarily unavailable. Please try again later.")
            in 300..399 -> RecognitionError.MalformedResponse(
                "Azure returned a redirect. Redirects are not followed to protect your API key.",
            )
            else -> RecognitionError.MalformedResponse("Azure Read returned an unexpected HTTP response ($status).")
        }
    }

    private fun serviceFailure(error: AzureReadServiceError?): RecognitionError {
        val codes = generateSequence(error) { it.innererror }.take(8)
            .map { it.code.lowercase(Locale.ROOT) }.toSet()
        return when {
            codes.any { it in setOf("unauthorized", "forbidden", "invalidsubscriptionkey") } ->
                RecognitionError.Authentication("Azure rejected access. Check the resource key and permissions.")
            codes.any { it in setOf("toomanyrequests", "ratelimitexceeded", "quotaexceeded") } ->
                RecognitionError.RateLimited("Azure Read has reached a rate or quota limit. Please try again later.")
            codes.any { it.startsWith("invalid") || it in setOf("notsupported", "modelnotfound", "resourcenotfound") } ->
                RecognitionError.Configuration(
                    "Azure could not analyze this image. Check the resource configuration or choose another image.",
                )
            else -> RecognitionError.Network("Azure Read could not complete recognition. Please try again later.")
        }
    }

    private suspend fun <T> captureFailure(block: suspend () -> T): Result<T> {
        return try {
            currentCoroutineContext().ensureActive()
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: RecognitionError) {
            Result.failure(error)
        } catch (_: Exception) {
            // Transport exceptions can contain request headers or response text. Do not retain them.
            Result.failure(RecognitionError.Network("Unable to contact Azure Read. Check your connection and try again."))
        }
    }

    internal companion object {
        const val API_VERSION = "2024-11-30"
        const val MODEL_ID = "prebuilt-read"
        const val ANALYSIS_TIMEOUT_MILLIS = 120_000L
        const val CONNECTION_TIMEOUT_MILLIS = 15_000L
        const val MAX_POLLS = 60
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val MODEL_PATH = "/documentintelligence/documentModels/$MODEL_ID"
        private const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
        private const val ENDPOINT_HELP =
            "Enter an HTTPS Azure Document Intelligence resource endpoint without a path, query or credentials."
        private val TRANSIENT_POLL_STATUSES = setOf(408, 429, 500, 502, 503, 504)
        private val AZURE_RESOURCE_HOST = Regex(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\." +
                "(?:cognitiveservices\\.azure\\.(?:com|us|cn)|" +
                "api\\.cognitive\\.microsoft\\.(?:com|us)|api\\.cognitive\\.azure\\.cn)",
        )
        private val OPERATION_PATH = Regex("$MODEL_PATH/analyzeResults/[A-Za-z0-9_-]+")

        private fun parseUri(value: String): URI? = try {
            URI(value)
        } catch (_: Exception) {
            null
        }

        /** Retry-After supports both delta seconds and HTTP dates; clamp to avoid busy loops or unbounded waits. */
        internal fun retryDelayMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long {
            val raw = value?.trim() ?: return 1_000L
            val seconds = raw.toLongOrNull()
            if (seconds != null) return seconds.coerceIn(1, 5) * 1_000L
            return try {
                val retryAt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (retryAt - nowMillis).coerceIn(1_000L, 5_000L)
            } catch (_: Exception) {
                1_000L
            }
        }
    }
}
