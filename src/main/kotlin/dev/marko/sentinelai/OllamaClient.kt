package dev.marko.sentinelai

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP wrapper around the Ollama REST API.
 *
 * Ollama endpoint:  POST http://localhost:11434/api/generate
 * Docs: https://github.com/ollama/ollama/blob/main/docs/api.md
 *
 * No external dependencies — uses Java 17 java.net.http.HttpClient.
 */
object OllamaClient {

    private val LOG = Logger.getInstance(OllamaClient::class.java)

    private const val DEFAULT_BASE_URL = "http://localhost:11434"
    private const val GENERATE_PATH    = "/api/generate"
    private const val CONNECT_TIMEOUT_SEC  = 3L
    private const val REQUEST_TIMEOUT_SEC  = 60L   // codellama:7b ~3-5s on a modern machine

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true  // tolerate minor JSON quirks from local models
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .build()

    // Public API

    /**
     * Send [prompt] to Ollama and return the raw text response.
     * Throws [OllamaException] on any network or HTTP error.
     */
    fun generate(
        prompt: String,
        systemPrompt: String,
        model: String = "codellama:7b",
        baseUrl: String = DEFAULT_BASE_URL
    ): String {
        val requestBody = json.encodeToString(
            OllamaRequest(
                model = model,
                prompt = prompt,
                system = systemPrompt,
                stream = false
            )
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$GENERATE_PATH"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        LOG.debug("SentinelAI → Ollama request: model=$model, prompt_length=${prompt.length}")

        val httpResponse = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw OllamaException("Cannot reach Ollama at $baseUrl — is it running?", e)
        }

        if (httpResponse.statusCode() != 200) {
            throw OllamaException(
                "Ollama returned HTTP ${httpResponse.statusCode()}: ${httpResponse.body().take(200)}"
            )
        }

        val ollamaResponse = try {
            json.decodeFromString<OllamaResponse>(httpResponse.body())
        } catch (e: Exception) {
            throw OllamaException("Failed to parse Ollama envelope: ${e.message}", e)
        }

        LOG.debug("SentinelAI ← Ollama response length: ${ollamaResponse.response.length}")
        return ollamaResponse.response
    }

    /**
     * Check whether Ollama is reachable and the requested model is available.
     * Returns null on success, or an error message string.
     */
    fun healthCheck(model: String = "codellama:7b", baseUrl: String = DEFAULT_BASE_URL): String? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/tags"))
                .timeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) return "Ollama not reachable (HTTP ${resp.statusCode()})"

            // Check that the required model is pulled
            val bodyJson = Json.parseToJsonElement(resp.body()).jsonObject
            val models = bodyJson["models"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                ?: emptyList()

            if (models.none { it.startsWith(model.substringBefore(":")) }) {
                "Model '$model' not found. Run: ollama pull $model"
            } else null

        } catch (e: Exception) {
            "Ollama unreachable: ${e.message}"
        }
    }
}

class OllamaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)