package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AzureReadProviderTest {
    private val endpoint = "https://journal.cognitiveservices.azure.com"
    private val config = AzureReadConfig(endpoint, "mock-key-not-a-secret")
    private val operationPath = "/documentintelligence/documentModels/prebuilt-read/analyzeResults/test-operation"
    private val operationUrl = "$endpoint$operationPath?api-version=2024-11-30"
    private val image = PreparedImage(byteArrayOf(1, 2, 3, 4), "image/png", 4, 2, IntArray(8))
    private val engines = mutableListOf<MockEngine>()
    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
        engines.forEach { it.close() }
    }

    @Test
    fun `submits actual image bytes and MIME using only stable Read and automatic language detection`() = runTest {
        val (provider, engine) = fixture(accepted(), response(success()))

        val result = provider.recognize(image).getOrThrow()

        assertEquals(RecognitionProviderId.AZURE, result.provider)
        assertEquals("prebuilt-read/2024-11-30", result.modelVersion)
        assertEquals(2, engine.requestHistory.size)
        val submission = engine.requestHistory.first()
        assertEquals(HttpMethod.Post, submission.method)
        assertEquals("/documentintelligence/documentModels/prebuilt-read:analyze", submission.url.encodedPath)
        assertEquals(setOf("api-version", "stringIndexType"), submission.url.parameters.names())
        assertEquals("2024-11-30", submission.url.parameters["api-version"])
        assertEquals("utf16CodeUnit", submission.url.parameters["stringIndexType"])
        val content = submission.body as OutgoingContent.ByteArrayContent
        assertArrayEquals(image.bytes, content.bytes())
        assertEquals(image.mimeType, content.contentType.toString())
        engine.requestHistory.forEach {
            assertEquals(config.apiKey, it.headers["Ocp-Apim-Subscription-Key"])
            assertEquals("journal.cognitiveservices.azure.com", it.url.host)
        }
        assertEquals(HttpMethod.Get, engine.requestHistory.last().method)
        assertEquals(operationUrl, engine.requestHistory.last().url.toString())
        assertEquals(listOf(ImagePoint(.1f, .1f), ImagePoint(.6f, .1f), ImagePoint(.6f, .3f), ImagePoint(.1f, .3f)),
            result.regions.single().polygon)
        assertEquals(.9f, result.regions.single().confidence)
    }

    @Test
    fun `preserves JPEG content type rather than assuming PNG`() = runTest {
        val (provider, engine) = fixture(accepted(), response(success()))
        provider.recognize(PreparedImage(image.bytes, "image/jpeg", image.width, image.height, image.pixels)).getOrThrow()
        assertEquals("image/jpeg", engine.requestHistory.first().body.contentType.toString())
    }

    @Test
    fun `polls pending statuses and observes bounded retry after delays`() = runTest {
        val (provider, engine) = fixture(
            accepted(retryAfter = "2"),
            response("""{"status":"notStarted"}""", retryAfter = "3"),
            response("""{"status":"running"}""", retryAfter = "0"),
            response(success()),
        )

        provider.recognize(image).getOrThrow()

        assertEquals(4, engine.requestHistory.size)
        assertEquals(6_000L, testScheduler.currentTime)
    }

    @Test
    fun `polling terminates after a finite attempt count`() = runTest {
        val replies = listOf(accepted()) +
            List(AzureReadProvider.MAX_POLLS) { response("""{"status":"running"}""") }
        val (provider, engine) = fixture(*replies.toTypedArray())

        assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.Network)
        assertEquals(AzureReadProvider.MAX_POLLS + 1, engine.requestHistory.size)
    }

    @Test
    fun `overall budget limits long retry after polling`() = runTest {
        val replies = listOf(accepted(retryAfter = "99999999")) +
            List(AzureReadProvider.MAX_POLLS) { response("""{"status":"running"}""", retryAfter = "99999999") }
        val (provider, engine) = fixture(*replies.toTypedArray())

        assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.Network)
        assertEquals(AzureReadProvider.ANALYSIS_TIMEOUT_MILLIS, testScheduler.currentTime)
        assertTrue(engine.requestHistory.size < AzureReadProvider.MAX_POLLS)
    }

    @Test
    fun `analysis budget includes a stalled submission`() = runTest {
        val engine = mockEngine { awaitCancellation() }
        val provider = AzureReadProvider(clientFor(engine), config)

        assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.Network)
        assertEquals(AzureReadProvider.ANALYSIS_TIMEOUT_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun `retry after accepts HTTP date and safely handles invalid negative and extreme values`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(3).atZone(ZoneOffset.UTC))
        assertEquals(3_000L, AzureReadProvider.retryDelayMillis(date, now.toEpochMilli()))
        listOf(null, "", "nonsense", "-4", "0", "99999999999999999999999999999").forEach {
            assertEquals(1_000L, AzureReadProvider.retryDelayMillis(it, now.toEpochMilli()), "header: $it")
        }
        assertEquals(5_000L, AzureReadProvider.retryDelayMillis(Long.MAX_VALUE.toString()))
    }

    @Test
    fun `authentication and rate limits are typed and never resubmit the image`() = runTest {
        listOf(401, 403, 429).forEach { status ->
            val (provider, engine) = fixture(response("""{"message":"${config.apiKey}"}""", status))
            val error = provider.recognize(image).exceptionOrNull()
            assertTrue(if (status == 429) error is RecognitionError.RateLimited else error is RecognitionError.Authentication)
            assertEquals(1, engine.requestHistory.size)
            assertFalse(error.toString().contains(config.apiKey))
            assertNull(error?.cause)
        }
    }

    @Test
    fun `authentication failure while polling is reported immediately`() = runTest {
        listOf(401, 403).forEach { status ->
            val (provider, engine) = fixture(accepted(), response("{}", status))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.Authentication)
            assertEquals(2, engine.requestHistory.size)
        }
    }

    @Test
    fun `transient polling errors retry only reads and can recover`() = runTest {
        val (provider, engine) = fixture(
            accepted(), response("{}", 429, "2"), response("{}", 503), response(success()),
        )
        provider.recognize(image).getOrThrow()
        assertEquals(1, engine.requestHistory.count { it.method == HttpMethod.Post })
        assertEquals(3, engine.requestHistory.count { it.method == HttpMethod.Get })
    }

    @Test
    fun `repeated polling throttling and transient failures are bounded`() = runTest {
        listOf(429, 503).forEach { status ->
            val (provider, engine) = fixture(accepted(), *Array(4) { response("{}", status) })
            val error = provider.recognize(image).exceptionOrNull()
            assertTrue(if (status == 429) error is RecognitionError.RateLimited else error is RecognitionError.Network)
            assertEquals(5, engine.requestHistory.size)
        }
    }

    @Test
    fun `terminal failure is typed without exposing service messages`() = runTest {
        val (provider, engine) = fixture(
            accepted(),
            response("""{"status":"failed","error":{"code":"InvalidRequest","message":"${config.apiKey}","innererror":{"code":"InvalidContent"}}}"""),
        )
        val error = provider.recognize(image).exceptionOrNull()
        assertTrue(error is RecognitionError.Configuration)
        assertFalse(error.toString().contains(config.apiKey))
        assertNull(error?.cause)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `missing blank duplicate and malformed operation locations are rejected before polling`() = runTest {
        val invalidHeaders = listOf(
            Headers.Empty,
            headersOf("Operation-Location", ""),
            headersOf("Operation-Location", "not a URI"),
            headersOf("Operation-Location", listOf(operationUrl, operationUrl)),
        )
        invalidHeaders.forEach { headers ->
            val (provider, engine) = fixture(ResponseSpec(202, "", headers))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse)
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `rejects foreign origins URL tricks unexpected paths and alternate API versions`() = runTest {
        val badUrls = listOf(
            operationUrl.replace("journal.", "other."),
            operationUrl.replace("cognitiveservices.azure.com", "cognitiveservices.azure.com.evil.example"),
            operationUrl.replace("https://", "http://"),
            operationUrl.replace("https://", "https://user:password@"),
            operationUrl.replace("https://", "https://evil.example@"),
            operationUrl.replace(".com/", ".com:444/"),
            operationUrl.replace(".com/", ".com.evil.example\\@journal.cognitiveservices.azure.com/"),
            operationUrl.replace("prebuilt-read/", "prebuilt-layout/"),
            operationUrl.replace("test-operation", "../test-operation"),
            operationUrl.replace("test-operation", "%2e%2e%2ftest-operation"),
            operationUrl.replace("2024-11-30", "2023-07-31"),
            "$operationUrl#fragment",
            "$operationUrl&features=styleFont",
            operationPath,
            "//journal.cognitiveservices.azure.com$operationPath",
        )
        badUrls.forEach { location ->
            val (provider, engine) = fixture(accepted(location))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse, location)
            assertEquals(1, engine.requestHistory.size, location)
        }
    }

    @Test
    fun `accepts same origin case changes and explicit HTTPS default port`() = runTest {
        val location = operationUrl.replace("journal.cognitiveservices.azure.com", "JOURNAL.cognitiveservices.azure.com:443")
        val (provider, _) = fixture(accepted(location), response(success()))
        assertTrue(provider.recognize(image).isSuccess)
    }

    @Test
    fun `invalid resource configuration fails locally without sending the key`() = runTest {
        val endpoints = listOf(
            "", "http://journal.cognitiveservices.azure.com", "https://evil.example",
            "$endpoint.evil.example", "https://cognitiveservices.azure.com",
            "https://journal.openai.azure.com", "$endpoint/path", "$endpoint?query",
            "$endpoint#fragment", "$endpoint:444", "https://user:pass@journal.cognitiveservices.azure.com",
            "https://journal.cognitiveservices.azure.com\\@evil.example",
        )
        endpoints.forEach { invalid ->
            val (provider, engine) = fixture(config = config.copy(endpoint = invalid))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.Configuration, invalid)
            assertTrue(engine.requestHistory.isEmpty())
        }
        listOf("", " ", "bad\nkey").forEach { key ->
            val (provider, engine) = fixture(config = config.copy(apiKey = key))
            assertTrue(provider.testConnection().exceptionOrNull() is RecognitionError.Configuration)
            assertTrue(engine.requestHistory.isEmpty())
        }
    }

    @Test
    fun `submission polling and connection redirects cannot forward subscription keys`() = runTest {
        val redirect = ResponseSpec(307, "", headersOf(HttpHeaders.Location, "https://evil.example/steal"))
        val (submission, submissionEngine) = fixture(redirect)
        assertTrue(submission.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse)
        assertEquals(1, submissionEngine.requestHistory.size)

        val (polling, pollingEngine) = fixture(accepted(), redirect)
        assertTrue(polling.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse)
        assertEquals(2, pollingEngine.requestHistory.size)

        val (connection, connectionEngine) = fixture(redirect)
        assertTrue(connection.testConnection().exceptionOrNull() is RecognitionError.MalformedResponse)
        assertEquals(1, connectionEngine.requestHistory.size)
    }

    @Test
    fun `unknown status invalid JSON missing result and missing required fields are malformed`() = runTest {
        listOf(
            "not json", "[]", "{}", """{"status":null}""",
            """{"status":"unknown"}""", """{"status":"succeeded"}""",
            """{"status":"succeeded","analyzeResult":{}}""",
            success().replace("\"polygon\":[40,20,240,20,240,60,40,60],", ""),
            success().replace("\"stringIndexType\":\"utf16CodeUnit\",", ""),
        ).forEach { body ->
            val (provider, _) = fixture(accepted(), response(body))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse, body)
        }
    }

    @Test
    fun `valid unknown fields and omitted optional structures do not break parsing`() = runTest {
        val body = success(
            resultOverrides = mapOf(
                "paragraphs" to JsonPrimitive(null),
                "styles" to JsonArray(emptyList()),
                "unknownFutureField" to buildJsonObject { put("nested", true) },
            ),
            pageOverrides = mapOf("words" to JsonPrimitive(null)),
        )
        val (provider, _) = fixture(accepted(), response(body))
        val region = provider.recognize(image).getOrThrow().regions.single()
        assertNull(region.paragraphId)
        assertNull(region.confidence)
    }

    @Test
    fun `normalizes Unicode offsets reading order and paragraph hints without splitting soft wraps`() = runTest {
        val texts = listOf("😀 Caffè", "più tè", "Second paragraph")
        val content = texts.joinToString("\n")
        val starts = listOf(0, texts[0].length + 1, texts[0].length + texts[1].length + 2)
        val orderedLines = texts.indices.map { line(texts[it], starts[it]) }
        val body = success(
            resultOverrides = mapOf(
                "content" to JsonPrimitive(content),
                "paragraphs" to JsonArray(listOf(
                    paragraph(texts.take(2).joinToString("\n"), 0),
                    paragraph(texts[2], starts[2]),
                )),
            ),
            pageOverrides = mapOf(
                "lines" to JsonArray(listOf(orderedLines[2], orderedLines[0], orderedLines[1])),
                "words" to JsonArray(emptyList()),
            ),
        )
        val (provider, _) = fixture(accepted(), response(body))

        val regions = provider.recognize(image).getOrThrow().regions

        assertEquals(texts, regions.map { it.text })
        assertEquals(regions[0].paragraphId, regions[1].paragraphId)
        assertEquals("azure-paragraph-0", regions[0].paragraphId)
        assertEquals("azure-paragraph-1", regions[2].paragraphId)
    }

    @Test
    fun `ambiguous paragraph membership is not fabricated`() = runTest {
        val body = success(resultOverrides = mapOf("paragraphs" to JsonArray(listOf(paragraph("Ciao", 0), paragraph("Ciao", 0)))))
        val (provider, _) = fixture(accepted(), response(body))
        assertNull(provider.recognize(image).getOrThrow().regions.single().paragraphId)
    }

    @Test
    fun `paragraph hints require full span coverage and allow adjacent spans`() = runTest {
        val partial = success(resultOverrides = mapOf("paragraphs" to JsonArray(listOf(paragraph("Ci", 0)))))
        val (unmatched, _) = fixture(accepted(), response(partial))
        assertNull(unmatched.recognize(image).getOrThrow().regions.single().paragraphId)

        val contiguous = jsonObject("""{"content":"Ciao","spans":[{"offset":0,"length":2},{"offset":2,"length":2}]}""")
        val (matched, _) = fixture(accepted(), response(success(
            resultOverrides = mapOf("paragraphs" to JsonArray(listOf(contiguous))),
        )))
        assertEquals("azure-paragraph-0", matched.recognize(image).getOrThrow().regions.single().paragraphId)
    }

    @Test
    fun `line confidence averages only contained word confidences`() = runTest {
        val body = success(
            resultOverrides = mapOf(
                "content" to JsonPrimitive("Ciao mondo"),
                "paragraphs" to JsonArray(emptyList()),
            ),
            pageOverrides = mapOf(
                "lines" to JsonArray(listOf(line("Ciao mondo", 0))),
                "words" to JsonArray(listOf(
                    jsonObject("""{"content":"Ciao","span":{"offset":0,"length":4},"confidence":0.6}"""),
                    jsonObject("""{"content":"mondo","span":{"offset":5,"length":5},"confidence":1.0}"""),
                )),
            ),
        )
        val (provider, _) = fixture(accepted(), response(body))
        assertEquals(.8f, provider.recognize(image).getOrThrow().regions.single().confidence)
    }

    @Test
    fun `normalizes service coordinates independently of page units and prepared pixel size`() = runTest {
        val body = success(pageOverrides = mapOf(
            "width" to JsonPrimitive(4), "height" to JsonPrimitive(2), "unit" to JsonPrimitive("inch"),
            "lines" to JsonArray(listOf(line("Ciao", 0, listOf(.4, .2, 2.4, .2, 2.4, .6, .4, .6)))),
        ))
        val (provider, _) = fixture(accepted(), response(body))
        val polygon = provider.recognize(image).getOrThrow().regions.single().polygon
        assertEquals(ImagePoint(.1f, .1f), polygon.first())
        assertEquals(ImagePoint(.6f, .3f), polygon[2])
    }

    @Test
    fun `malformed geometry confidence and spans are rejected`() = runTest {
        val invalidLines = listOf(
            line("Ciao", 0, emptyList()),
            line("Ciao", 0, listOf(1, 2, 3)),
            line("Ciao", 0, listOf(1, 2, 1, 2, 1, 2)),
            line("Ciao", 0, listOf(-1, 2, 4, 2, 4, 6, 1, 6)),
            line("Ciao", 0, listOf(401, 2, 4, 2, 4, 6, 1, 6)),
            JsonObject(line("Ciao", 0) + ("confidence" to JsonPrimitive(1.1))),
            JsonObject(line("Ciao", 0) + ("confidence" to JsonPrimitive(-.1))),
            line("Ciao", -1),
            line("Ciao", Int.MAX_VALUE),
            JsonObject(line("Ciao", 0) + ("spans" to JsonArray(emptyList()))),
        )
        invalidLines.forEach { badLine ->
            val (provider, _) = fixture(accepted(), response(success(pageOverrides = mapOf("lines" to JsonArray(listOf(badLine))))))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse, badLine.toString())
        }
        val invalidWord = jsonObject("""{"content":"Ciao","span":{"offset":0,"length":4},"confidence":2}""")
        val (provider, _) = fixture(accepted(), response(success(pageOverrides = mapOf("words" to JsonArray(listOf(invalidWord))))))
        assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse)
    }

    @Test
    fun `invalid page extents index units and response provenance are rejected`() = runTest {
        listOf(
            success(pageOverrides = mapOf("width" to JsonPrimitive(0))),
            success(pageOverrides = mapOf("height" to JsonPrimitive(-1))),
            success(pageOverrides = mapOf("pageNumber" to JsonPrimitive(2))),
            success(pageOverrides = mapOf("unit" to JsonPrimitive("unknown"))),
            success(resultOverrides = mapOf("modelId" to JsonPrimitive("prebuilt-layout"))),
            success(resultOverrides = mapOf("apiVersion" to JsonPrimitive("preview"))),
            success(resultOverrides = mapOf("stringIndexType" to JsonPrimitive("unicodeCodePoint"))),
            success(resultOverrides = mapOf("pages" to JsonArray(emptyList()))),
            success().replace("\"width\":400", "\"width\":1e309"),
            success().replace("\"confidence\":0.9", "\"confidence\":1e309"),
            success(pageOverrides = mapOf("lines" to JsonArray(listOf(line("abcd", 0))))),
        ).forEach { body ->
            val (provider, _) = fixture(accepted(), response(body))
            assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse, body)
        }
    }

    @Test
    fun `spans cannot bisect a Unicode surrogate pair`() = runTest {
        val body = success(
            resultOverrides = mapOf("content" to JsonPrimitive("😀abc"), "paragraphs" to JsonArray(emptyList())),
            pageOverrides = mapOf("lines" to JsonArray(listOf(line("abc", 1))), "words" to JsonArray(emptyList())),
        )
        val (provider, _) = fixture(accepted(), response(body))
        assertTrue(provider.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse)
    }

    @Test
    fun `empty recognition returns no regions whereas missing geometry for text is an error`() = runTest {
        val body = success(
            resultOverrides = mapOf("content" to JsonPrimitive(""), "paragraphs" to JsonArray(emptyList())),
            pageOverrides = mapOf("lines" to JsonPrimitive(null), "words" to JsonPrimitive(null)),
        )
        val (empty, _) = fixture(accepted(), response(body))
        assertTrue(empty.recognize(image).getOrThrow().regions.isEmpty())
        val (missing, _) = fixture(accepted(), response(success(pageOverrides = mapOf("lines" to JsonPrimitive(null)))))
        assertTrue(missing.recognize(image).exceptionOrNull() is RecognitionError.MalformedResponse)
    }

    @Test
    fun `transport failures redact exception messages and causes`() = runTest {
        val engine = mockEngine { throw IOException("Ocp-Apim-Subscription-Key: ${config.apiKey}") }
        val provider = AzureReadProvider(clientFor(engine), config)
        val error = provider.recognize(image).exceptionOrNull()
        assertTrue(error is RecognitionError.Network)
        assertFalse(error.toString().contains(config.apiKey))
        assertNull(error?.cause)
    }

    @Test
    fun `engine cancellation always propagates instead of becoming a failure result`() = runTest {
        val cancellation = CancellationException("Recognition canceled")
        val engine = mockEngine { throw cancellation }
        val provider = AzureReadProvider(clientFor(engine), config)
        val thrown = try {
            provider.recognize(image)
            null
        } catch (error: CancellationException) {
            error
        }
        assertPropagatedCancellation(cancellation, thrown)
    }

    @Test
    fun `caller cancellation during pending recognition stops further work`() = runTest {
        val submitted = CompletableDeferred<Unit>()
        val engine = mockEngine {
            submitted.complete(Unit)
            respond("", HttpStatusCode.Accepted, headersOf("Operation-Location", operationUrl))
        }
        val provider = AzureReadProvider(clientFor(engine), config)
        var returned = false
        val job = launch {
            provider.recognize(image)
            returned = true
        }
        submitted.await()
        job.cancelAndJoin()
        assertFalse(returned)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `connection probe verifies model metadata with no image or analysis submission`() = runTest {
        val (provider, engine) = fixture(response("""{"modelId":"prebuilt-read","description":"OCR","docTypes":{}}"""))
        assertTrue(provider.testConnection().isSuccess)
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/documentintelligence/documentModels/prebuilt-read", request.url.encodedPath)
        assertEquals("2024-11-30", request.url.parameters["api-version"])
        assertEquals(config.apiKey, request.headers["Ocp-Apim-Subscription-Key"])
        assertTrue(request.body is OutgoingContent.NoContent)
    }

    @Test
    fun `probe rejects authentication rate limits malformed metadata and wrong models`() = runTest {
        listOf(401, 403, 429).forEach { status ->
            val (provider, _) = fixture(response("{}", status))
            val error = provider.testConnection().exceptionOrNull()
            assertTrue(if (status == 429) error is RecognitionError.RateLimited else error is RecognitionError.Authentication)
        }
        listOf("{}", "not JSON", """{"modelId":"prebuilt-layout"}""").forEach { body ->
            val (provider, _) = fixture(response(body))
            assertTrue(provider.testConnection().exceptionOrNull() is RecognitionError.MalformedResponse)
        }
    }

    @Test
    fun `connection budget is finite and engine cancellation propagates`() = runTest {
        val provider = AzureReadProvider(clientFor(mockEngine { awaitCancellation() }), config)
        assertTrue(provider.testConnection().exceptionOrNull() is RecognitionError.Network)
        assertEquals(AzureReadProvider.CONNECTION_TIMEOUT_MILLIS, testScheduler.currentTime)

        val cancellation = CancellationException("Probe canceled")
        val canceled = AzureReadProvider(clientFor(mockEngine { throw cancellation }), config)
        val thrown = try {
            canceled.testConnection()
            null
        } catch (error: CancellationException) {
            error
        }
        assertPropagatedCancellation(cancellation, thrown)
    }

    @Test
    fun `caller cancellation during the connection probe stops work`() = runTest {
        val requested = CompletableDeferred<Unit>()
        val engine = mockEngine {
            requested.complete(Unit)
            awaitCancellation()
        }
        val provider = AzureReadProvider(clientFor(engine), config)
        var returned = false
        val job = launch {
            provider.testConnection()
            returned = true
        }

        requested.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returned)
    }

    @Test
    fun `provider does not close the injected client or reuse its redirect settings`() = runTest {
        val engine = mockEngine { respond("""{"modelId":"prebuilt-read"}""") }
        val client = clientFor(engine)
        val provider = AzureReadProvider(client, config)
        assertTrue(provider.testConnection().isSuccess)
        assertTrue(provider.testConnection().isSuccess)
        assertEquals(2, engine.requestHistory.size)
    }

    private data class ResponseSpec(val status: Int, val body: String, val headers: Headers)

    private fun assertPropagatedCancellation(expected: CancellationException, actual: CancellationException?) {
        assertTrue(actual != null, "Cancellation must be thrown, not returned as a Result")
        assertEquals(expected.message, actual?.message)
        // Coroutine stack-trace recovery may copy the exception while retaining the original as its cause.
        assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === expected })
    }

    private fun response(body: String, status: Int = 200, retryAfter: String? = null): ResponseSpec =
        ResponseSpec(status, body, Headers.build {
            append(HttpHeaders.ContentType, "application/json")
            if (retryAfter != null) append(HttpHeaders.RetryAfter, retryAfter)
        })

    private fun accepted(location: String = operationUrl, retryAfter: String? = null): ResponseSpec =
        ResponseSpec(202, "", Headers.build {
            append("Operation-Location", location)
            if (retryAfter != null) append(HttpHeaders.RetryAfter, retryAfter)
        })

    private fun TestScope.fixture(vararg replies: ResponseSpec, config: AzureReadConfig = this@AzureReadProviderTest.config): Pair<AzureReadProvider, MockEngine> {
        var requestIndex = 0
        val engine = mockEngine {
            check(requestIndex < replies.size) { "Unexpected extra HTTP request" }
            val response = replies[requestIndex++]
            respond(response.body, HttpStatusCode.fromValue(response.status), response.headers)
        }
        return AzureReadProvider(clientFor(engine), config) to engine
    }

    private fun TestScope.mockEngine(handler: MockRequestHandler): MockEngine =
        MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        })

    private fun clientFor(engine: MockEngine): HttpClient {
        engines += engine
        return HttpClient(engine) {
            followRedirects = true
            expectSuccess = true
        }.also { clients += it }
    }

    private fun jsonObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun line(
        content: String,
        offset: Int,
        polygon: List<Number> = listOf(40, 20, 240, 20, 240, 60, 40, 60),
    ): JsonObject = buildJsonObject {
        put("content", content)
        put("polygon", JsonArray(polygon.map { JsonPrimitive(it) }))
        put("spans", JsonArray(listOf(jsonObject("""{"offset":$offset,"length":${content.length}}"""))))
    }

    private fun paragraph(content: String, offset: Int): JsonObject = buildJsonObject {
        put("content", content)
        put("spans", JsonArray(listOf(jsonObject("""{"offset":$offset,"length":${content.length}}"""))))
    }

    private fun success(
        resultOverrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        pageOverrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): String {
        val page = JsonObject(jsonObject("""
            {
              "pageNumber":1,"width":400,"height":200,"unit":"pixel",
              "lines":[],
              "words":[{"content":"Ciao","span":{"offset":0,"length":4},"confidence":0.9}]
            }
        """) + ("lines" to JsonArray(listOf(line("Ciao", 0)))) + pageOverrides)
        val result = JsonObject(buildJsonObject {
            put("apiVersion", "2024-11-30")
            put("modelId", "prebuilt-read")
            put("stringIndexType", "utf16CodeUnit")
            put("content", "Ciao")
            put("pages", JsonArray(listOf(page)))
            put("paragraphs", JsonArray(listOf(paragraph("Ciao", 0))))
        } + resultOverrides)
        return buildJsonObject {
            put("status", "succeeded")
            put("analyzeResult", result)
        }.toString()
    }
}
