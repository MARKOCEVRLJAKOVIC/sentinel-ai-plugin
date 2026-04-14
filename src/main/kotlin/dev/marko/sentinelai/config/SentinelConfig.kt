package dev.marko.sentinelai.config

import com.intellij.openapi.diagnostic.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File

private val LOG = Logger.getInstance(SentinelConfig::class.java)

object SentinelConfig {

    // Defaults
    var aiProvider       : String          = "anthropic"                  ; private set
    var aiModel          : String          = "claude-haiku-4-5"          ; private set
    var apiKeyEnvVar     : String          = "SENTINEL_API_KEY"          ; private set
    var aiTimeoutSeconds : Int             = 10                           ; private set
    var timeoutBehavior  : TimeoutBehavior = TimeoutBehavior.WARN        ; private set
    var blockOn          : Set<String>     = setOf("api_keys", "system_prompts", "private_keys", "db_credentials") ; private set
    var warnOn           : Set<String>     = setOf("pii", "debug_logs") ; private set
    var ignorePatterns   : List<Regex>     = emptyList()                 ; private set
    var neverSendToCloud : List<Regex>     = listOf(
        Regex(".*\\.pem$"),
        Regex(".*\\.key$"),
        Regex(".*secrets.*"),
        Regex(".*credentials.*")
    )                                                                     ; private set
    var customPatterns   : List<CustomPattern> = emptyList()            ; private set

    /**
     * Resolve the API key from the environment variable configured in .sentinel.yml.
     * NEVER reads the key from the config file itself.
     */
    val apiKey: String
        get() = System.getenv(apiKeyEnvVar) ?: ""

    // Reload from disk

    fun reload(projectBasePath: String) {
        val configFile = File("$projectBasePath/.sentinel.yml")
        if (!configFile.exists()) {
            LOG.info("SentinelAI: No .sentinel.yml found — using defaults")
            return
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val root     = Yaml().load<Map<String, Any>>(configFile.readText())
            val sentinel = root["sentinel"] as? Map<*, *> ?: return

            aiProvider       = sentinel["ai_provider"]?.toString()          ?: aiProvider
            aiModel          = sentinel["ai_model"]?.toString()             ?: aiModel
            apiKeyEnvVar     = sentinel["api_key_env"]?.toString()          ?: apiKeyEnvVar
            aiTimeoutSeconds = (sentinel["ai_timeout_seconds"] as? Int)     ?: aiTimeoutSeconds

            timeoutBehavior = when (sentinel["timeout_behavior"]?.toString()?.lowercase()) {
                "block" -> TimeoutBehavior.BLOCK
                "allow" -> TimeoutBehavior.ALLOW
                else    -> TimeoutBehavior.WARN
            }

            @Suppress("UNCHECKED_CAST")
            blockOn = (sentinel["block_on"] as? List<String>)?.toSet() ?: blockOn

            @Suppress("UNCHECKED_CAST")
            warnOn = (sentinel["warn_on"] as? List<String>)?.toSet() ?: warnOn

            @Suppress("UNCHECKED_CAST")
            ignorePatterns = (sentinel["ignore_patterns"] as? List<String>)
                ?.mapNotNull { runCatching { Regex(globToRegex(it)) }.getOrNull() }
                ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            neverSendToCloud = (sentinel["never_send_to_cloud"] as? List<String>)
                ?.mapNotNull { runCatching { Regex(globToRegex(it)) }.getOrNull() }
                ?: neverSendToCloud

            customPatterns = parseCustomPatterns(sentinel["custom_patterns"])

            LOG.info("SentinelAI: Loaded .sentinel.yml — provider=$aiProvider, model=$aiModel, timeout=${aiTimeoutSeconds}s")

        } catch (e: Exception) {
            LOG.warn("SentinelAI: Failed to parse .sentinel.yml — ${e.message}. Using defaults.")
        }
    }

    fun shouldIgnore(filePath: String): Boolean =
        ignorePatterns.any { it.containsMatchIn(filePath) }



    private fun globToRegex(glob: String): String {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**", i) -> { sb.append(".*");    i += 2 }
                glob[i] == '*'           -> { sb.append("[^/]*"); i++    }
                glob[i] == '?'           -> { sb.append("[^/]");  i++    }
                glob[i] == '.'           -> { sb.append("\\.");   i++    }
                else -> { sb.append(Regex.escape(glob[i].toString())); i++ }
            }
        }
        sb.append("$")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCustomPatterns(raw: Any?): List<CustomPattern> {
        val list = raw as? List<Map<*, *>> ?: return emptyList()
        return list.mapNotNull { entry ->
            val name        = entry["name"]?.toString()             ?: return@mapNotNull null
            val description = entry["description"]?.toString()      ?: ""
            val keywords    = (entry["keywords"] as? List<String>)  ?: emptyList()
            CustomPattern(name, description, keywords)
        }
    }

}

data class CustomPattern(
    val name: String,
    val description: String,
    val keywords: List<String>
)

enum class TimeoutBehavior { WARN, BLOCK, ALLOW }