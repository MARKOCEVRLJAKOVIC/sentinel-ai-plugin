package dev.marko.sentinelai.ai

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP wrapper around the Anthropic Messages API.
 *
 * Endpoint:  POST https://api.anthropic.com/v1/messages
 * Docs:      https://docs.anthropic.com/en/api/messages
 *
 * All public methods are suspend functions — they never block the calling thread.
 * Uses Java 17 [HttpClient.sendAsync] under the hood.
 */
object ClaudeClient {

    private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val CONNECT_TIMEOUT_SEC = 5L
    private const val REQUEST_TIMEOUT_SEC = 30L

    private const val MAX_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .build()

    private val LOG = Logger.getInstance(ClaudeClient::class.java)

    // Public API

    /**
     * Send [userPrompt] with [systemPrompt] to Claude and return the raw text response.
     *
     * This is a suspend function — it will not block the calling thread while
     * waiting for the HTTP response or during retry backoff.
     *
     * @throws ClaudeException on any network, HTTP, or API error
     */
    suspend fun analyze(
        apiKey: String,
        userPrompt: String,
        systemPrompt: String,
        model: String = "claude-haiku-4-5",
        maxTokens: Int = 1024
    ): String {
        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                }
            }
        }.toString()

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(ANTHROPIC_API_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        LOG.debug("Claude request: model=$model, prompt_length=${userPrompt.length}")

        return executeWithRetry(httpRequest)
    }

    /**
     * Check whether the Anthropic API is reachable and the API key is valid.
     * Returns null on success, or an error message string.
     */
    suspend fun healthCheck(apiKey: String, model: String = "claude-haiku-4-5"): String? {
        if (apiKey.isBlank()) {
            return "No API key configured. Set the SENTINEL_API_KEY environment variable."
        }

        return try {
            val requestBody = buildJsonObject {
                put("model", model)
                put("max_tokens", 10)
                put("system", "Reply with OK")
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    }
                }
            }.toString()

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val resp = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).await()
            when (resp.statusCode()) {
                200 -> null
                401 -> "Invalid API key. Check your SENTINEL_API_KEY environment variable."
                403 -> "API key lacks permissions. Check your Anthropic account."
                else -> "Anthropic API returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}"
            }
        } catch (e: Exception) {
            "Cannot reach Anthropic API: ${e.message}"
        }
    }

    // Internal

    private suspend fun executeWithRetry(request: HttpRequest): String {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val httpResponse = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()

                when (httpResponse.statusCode()) {
                    200 -> {
                        val text = extractTextFromResponse(httpResponse.body())
                        LOG.debug("Claude response length: ${text.length}")
                        return text
                    }
                    429 -> {
                        val backoff = RETRY_DELAYS_MS.getOrElse(attempt) { 4_000 }
                        LOG.warn("Claude rate limited (429), retrying in ${backoff}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                        delay(backoff)
                        continue
                    }
                    401 -> throw ClaudeException(
                        "Invalid API key. Check your SENTINEL_API_KEY environment variable."
                    )
                    else -> throw ClaudeException(
                        "Claude API returned HTTP ${httpResponse.statusCode()}: ${httpResponse.body().take(200)}"
                    )
                }
            } catch (e: ClaudeException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    val backoff = RETRY_DELAYS_MS.getOrElse(attempt) { 4_000 }
                    LOG.warn("Claude request failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}, retrying in ${backoff}ms")
                    delay(backoff)
                }
            }
        }

        throw ClaudeException(
            "Cannot reach Anthropic API after $MAX_RETRIES attempts — ${lastException?.message}",
            lastException
        )
    }

    /**
     * Extract the text content from Claude's Messages API response.
     */
    private fun extractTextFromResponse(responseBody: String): String {
        return try {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val content = root["content"]?.jsonArray
                ?: throw ClaudeException("No 'content' field in Claude response")

            content
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
        } catch (e: ClaudeException) {
            throw e
        } catch (e: Exception) {
            throw ClaudeException("Failed to parse Claude response envelope: ${e.message}", e)
        }
    }
}

class ClaudeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
