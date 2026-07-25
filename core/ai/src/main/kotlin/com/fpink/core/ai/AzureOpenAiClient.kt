package com.fpink.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AzureOpenAiClient(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun analysePage(
        config: AiConfig,
        imageBytes: ByteArray,
        mimeType: String,
    ): Result<PageAnalysis> = runCatching {
        val base64Image = Base64.Default.encode(imageBytes)
        val dataUrl = "data:$mimeType;base64,$base64Image"

        val requestBody = buildJsonObject {
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", PromptTemplates.systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Please transcribe this handwritten page.")
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", dataUrl)
                                put("detail", "high")
                            }
                        }
                    }
                }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "page_analysis")
                    put("strict", true)
                    put("schema", anyToJsonElement(PromptTemplates.responseSchema))
                }
            }
        }

        val url = "${config.endpoint.trimEnd('/')}/openai/deployments/${config.deployment}/chat/completions?api-version=${config.apiVersion}"

        val response: HttpResponse = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            header("api-key", config.apiKey)
            setBody(requestBody.toString())
        }

        when (response.status.value) {
            200 -> parseSuccessResponse(response.bodyAsText())
            401, 403 -> throw AiError.AuthError("Authentication failed: ${response.status}")
            429 -> throw AiError.RateLimited("Rate limited. Please try again later.")
            else -> throw AiError.Unknown("Unexpected status: ${response.status}")
        }
    }.recoverCatching { e ->
        when (e) {
            is AiError -> throw e
            is java.io.IOException -> throw AiError.NetworkError("Network error: ${e.message}", e)
            else -> throw AiError.Unknown("Unexpected error: ${e.message}", e)
        }
    }

    internal fun parseSuccessResponse(responseBody: String): PageAnalysis {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val choices = root["choices"]?.jsonArray
                ?: throw AiError.MalformedResponse("Missing 'choices' in response")
            val firstChoice = choices.firstOrNull()?.jsonObject
                ?: throw AiError.MalformedResponse("Empty 'choices' array")
            val message = firstChoice["message"]?.jsonObject
                ?: throw AiError.MalformedResponse("Missing 'message' in choice")
            val content = message["content"]?.jsonPrimitive?.content
                ?: throw AiError.MalformedResponse("Missing 'content' in message")
            json.decodeFromString<PageAnalysis>(content)
        } catch (e: AiError) {
            throw e
        } catch (e: Exception) {
            throw AiError.MalformedResponse("Failed to parse response: ${e.message}", e)
        }
    }
}

private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) })
    is Iterable<*> -> JsonArray(value.map { anyToJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}
