package com.fpink.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AzureOpenAiClientTest {

    private val config = AiConfig(
        endpoint = "https://my-resource.openai.azure.com",
        deployment = "gpt-4o",
        apiVersion = "2024-12-01-preview",
        apiKey = "test-key",
    )

    private val imageBytes = byteArrayOf(1, 2, 3, 4)

    private fun clientReturning(status: HttpStatusCode, body: String): AzureOpenAiClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return AzureOpenAiClient(HttpClient(engine))
    }

    private fun analysisContent(inner: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("content", inner)
                }
            }
        }
    }.toString()

    @Test
    fun `happy path parses all fields`() = runTest {
        val inner =
            """{"text":"Hello world\nSecond line","ink_color_hex":"#1E3A5F","ink_color_name":"midnight blue","confidence":0.92,"notes":"Clean handwriting"}"""
        val client = clientReturning(HttpStatusCode.OK, analysisContent(inner))

        val result = client.analysePage(config, imageBytes, "image/jpeg")

        assertTrue(result.isSuccess, "Expected success but was $result")
        val analysis = result.getOrThrow()
        assertEquals("Hello world\nSecond line", analysis.text)
        assertEquals("#1E3A5F", analysis.inkColorHex)
        assertEquals("midnight blue", analysis.inkColorName)
        assertEquals(0.92f, analysis.confidence)
        assertEquals("Clean handwriting", analysis.notes)
    }

    @Test
    fun `happy path with only required text field`() = runTest {
        val inner = """{"text":"Just text"}"""
        val client = clientReturning(HttpStatusCode.OK, analysisContent(inner))

        val result = client.analysePage(config, imageBytes, "image/png")

        assertTrue(result.isSuccess, "Expected success but was $result")
        val analysis = result.getOrThrow()
        assertEquals("Just text", analysis.text)
        assertEquals(null, analysis.inkColorHex)
        assertEquals(null, analysis.confidence)
    }

    @Test
    fun `malformed content json yields MalformedResponse`() = runTest {
        val client = clientReturning(HttpStatusCode.OK, analysisContent("not json at all"))

        val result = client.analysePage(config, imageBytes, "image/jpeg")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AiError.MalformedResponse, "Expected MalformedResponse but was $error")
    }

    @Test
    fun `http 401 yields AuthError`() = runTest {
        val client = clientReturning(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""")

        val result = client.analysePage(config, imageBytes, "image/jpeg")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AiError.AuthError, "Expected AuthError but was $error")
    }

    @Test
    fun `http 403 yields AuthError`() = runTest {
        val client = clientReturning(HttpStatusCode.Forbidden, """{"error":"forbidden"}""")

        val result = client.analysePage(config, imageBytes, "image/jpeg")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiError.AuthError)
    }

    @Test
    fun `http 429 yields RateLimited`() = runTest {
        val client = clientReturning(HttpStatusCode.TooManyRequests, """{"error":"slow down"}""")

        val result = client.analysePage(config, imageBytes, "image/jpeg")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AiError.RateLimited, "Expected RateLimited but was $error")
    }

    @Test
    fun `unexpected status yields Unknown`() = runTest {
        val client = clientReturning(HttpStatusCode.InternalServerError, """{"error":"boom"}""")

        val result = client.analysePage(config, imageBytes, "image/jpeg")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AiError.Unknown)
    }

    // --- Direct parseSuccessResponse edge cases ---

    @Test
    fun `parseSuccessResponse parses valid body`() {
        val client = AzureOpenAiClient(HttpClient(MockEngine { respond("") }))
        val body = analysisContent("""{"text":"line one","confidence":0.5}""")

        val analysis = client.parseSuccessResponse(body)

        assertEquals("line one", analysis.text)
        assertEquals(0.5f, analysis.confidence)
    }

    @Test
    fun `parseSuccessResponse missing choices throws MalformedResponse`() {
        val client = AzureOpenAiClient(HttpClient(MockEngine { respond("") }))
        val error = runCatching { client.parseSuccessResponse("""{"foo":"bar"}""") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is AiError.MalformedResponse, "was $error")
    }

    @Test
    fun `parseSuccessResponse empty choices throws MalformedResponse`() {
        val client = AzureOpenAiClient(HttpClient(MockEngine { respond("") }))
        val error = runCatching { client.parseSuccessResponse("""{"choices":[]}""") }.exceptionOrNull()
        assertTrue(error is AiError.MalformedResponse, "was $error")
    }

    @Test
    fun `parseSuccessResponse missing content throws MalformedResponse`() {
        val client = AzureOpenAiClient(HttpClient(MockEngine { respond("") }))
        val body = """{"choices":[{"message":{}}]}"""
        val error = runCatching { client.parseSuccessResponse(body) }.exceptionOrNull()
        assertTrue(error is AiError.MalformedResponse, "was $error")
    }

    @Test
    fun `parseSuccessResponse invalid inner json throws MalformedResponse`() {
        val client = AzureOpenAiClient(HttpClient(MockEngine { respond("") }))
        val body = analysisContent("this is not json")
        val error = runCatching { client.parseSuccessResponse(body) }.exceptionOrNull()
        assertTrue(error is AiError.MalformedResponse, "was $error")
    }
}
