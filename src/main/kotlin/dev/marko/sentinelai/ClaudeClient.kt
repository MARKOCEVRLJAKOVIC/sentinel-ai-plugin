package dev.marko.sentinelai

import com.intellij.openapi.diagnostic.Logger
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
 * No external dependencies — uses Java 17 java.net.http.HttpClient.
 */
object ClaudeClient {

    private val LOG = Logger.getInstance(ClaudeClient::class.java)

    private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val CONNECT_TIMEOUT_SEC = 5L
    private const val REQUEST_TIMEOUT_SEC = 30L   // Claude Haiku ~1s, generous timeout

    private const val MAX_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)  // exponential backoff

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .build()

    // Public API

    /**
     * Send [userPrompt] with [systemPrompt] to Claude and return the raw text response.
     *
     * @param apiKey       Anthropic API key (sk-ant-...)
     * @param model        Model name, e.g. "claude-haiku-4-5"
     * @param userPrompt   The user-turn content (diff + instructions)
     * @param systemPrompt The system-turn content (role definition)
     * @param maxTokens    Maximum tokens in the response
     *
     * @throws ClaudeException on any network, HTTP, or API error
     */
    fun analyze(
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

        LOG.debug("SentinelAI → Claude request: model=$model, prompt_length=${userPrompt.length}")

        return executeWithRetry(httpRequest)
    }

    /**
     * Check whether the Anthropic API is reachable and the API key is valid.
     * Returns null on success, or an error message string.
     */
    fun healthCheck(apiKey: String, model: String = "claude-haiku-4-5"): String? {
        if (apiKey.isBlank()) {
            return "No API key configured. Set the SENTINEL_API_KEY environment variable."
        }

        return try {
            // Send a minimal request to verify the API key works
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

            val resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode()) {
                200 -> null   // all good
                401 -> "Invalid API key. Check your SENTINEL_API_KEY environment variable."
                403 -> "API key lacks permissions. Check your Anthropic account."
                else -> "Anthropic API returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}"
            }
        } catch (e: Exception) {
            "Cannot reach Anthropic API: ${e.message}"
        }
    }

    // Internal

    private fun executeWithRetry(request: HttpRequest): String {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                when (httpResponse.statusCode()) {
                    200 -> {
                        val text = extractTextFromResponse(httpResponse.body())
                        LOG.debug("SentinelAI ← Claude response length: ${text.length}")
                        return text
                    }
                    429 -> {
                        // Rate limited — retry with backoff
                        val delay = RETRY_DELAYS_MS.getOrElse(attempt) { 4_000 }
                        LOG.warn("SentinelAI: Claude rate limited (429), retrying in ${delay}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                        Thread.sleep(delay)
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
                throw e  // don't retry on auth errors
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    val delay = RETRY_DELAYS_MS.getOrElse(attempt) { 4_000 }
                    LOG.warn("SentinelAI: Claude request failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e.message}, retrying in ${delay}ms")
                    Thread.sleep(delay)
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
     *
     * Response format:
     * ```json
     * {
     *   "content": [{ "type": "text", "text": "..." }],
     *   ...
     * }
     * ```
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
