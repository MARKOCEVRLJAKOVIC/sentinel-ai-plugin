package dev.marko.sentinelai.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import dev.marko.sentinelai.scan.RiskLevel
import dev.marko.sentinelai.scan.RiskMapEngine
import kotlinx.serialization.json.Json

private val LOG = Logger.getInstance("SentinelAI.PromptEngine")

// Maximum characters of diff to send per file (keeps prompts within context window)
private const val MAX_DIFF_CHARS_PER_FILE = 2_000
private const val MAX_FILES_PER_PROMPT    = 6

// System prompt (sent once, static)

val SENTINEL_SYSTEM_PROMPT = """
You are a security-focused code reviewer. Your ONLY job is to detect
security leaks in code diffs.

You must respond with ONLY valid JSON. No explanations, no markdown,
no preamble. Just the JSON object.

Categories you detect:
- SYSTEM_PROMPT: LLM system prompts or AI instructions hardcoded in code
- API_KEY: API keys, tokens, secrets, passwords
- PII: Personal data in logs (emails, passwords, phone numbers)
- DB_CREDENTIAL: Database connection strings with credentials
- PRIVATE_KEY: Cryptographic keys or certificates

Severity levels:
- CRITICAL: Must be fixed before any push
- HIGH: Strong recommendation to fix
- MEDIUM: Worth reviewing

Do NOT report false positives on:
- Placeholder values like 'your-api-key-here' or 'TODO'
- Test mock data clearly labeled as fake
- Environment variable references like ${'$'}{API_KEY} or System.getenv()
""".trimIndent()

// PromptBuilder

object PromptBuilder {

    /**
     * Build the user-turn prompt from a list of high-risk files.
     *
     * Strategy:
     *  - For each file we compute the "diff" — currently we send the full
     *    content prefixed with `+` (Level 2 runs after commit, we analyse
     *    the new state). This simulates what a diff would look like.
     *  - Only lines that don't start with `-` are included (added/context).
     *  - Content is capped at [MAX_DIFF_CHARS_PER_FILE] per file.
     */
    fun buildScanPrompt(files: List<FileWithContent>): String {
        val sb = StringBuilder()
        sb.appendLine("Analyze the following code diff for security leaks.")
        sb.appendLine("Only analyze the ADDED lines (starting with +).")
        sb.appendLine("Ignore removed lines (starting with -).")
        sb.appendLine()

        files.take(MAX_FILES_PER_PROMPT).forEach { fileCtx ->
            val diff = extractAddedLines(fileCtx.content, fileCtx.rawDiff)
                .take(MAX_DIFF_CHARS_PER_FILE)

            sb.appendLine("FILE: ${fileCtx.relativePath} [Risk: ${fileCtx.riskLevel}]")
            sb.appendLine("DIFF:")
            sb.appendLine(diff)
            sb.appendLine()
        }

        sb.appendLine("""
Respond with this exact JSON structure:
{
  "findings": [
    {
      "file": "path/to/file.kt",
      "line": 42,
      "category": "API_KEY",
      "severity": "CRITICAL",
      "description": "Hardcoded API key detected",
      "snippet": "val apiKey = \"sk-proj-abc123...\"",
      "suggestion": "Use: val apiKey = System.getenv(\"API_KEY\")"
    }
  ],
  "analyzed_files": 1
}

If no security issues are found, respond with: {"findings": [], "analyzed_files": ${files.size}}
        """.trimIndent())

        return sb.toString()
    }

    /**
     * Build a "deep scan" prompt for a single file — sends broader context
     * beyond just the diff, up to [MAX_DIFF_CHARS_PER_FILE] chars.
     */
    fun buildDeepScanPrompt(file: FileWithContent): String {
        val content = file.content.take(MAX_DIFF_CHARS_PER_FILE)
        return """
Perform a deep security scan of the ENTIRE file below (not just a diff).
Look for any hardcoded secrets, credentials, or sensitive data anywhere in the file.

FILE: ${file.relativePath} [Risk: ${file.riskLevel}]
CONTENT:
$content

${buildJsonInstructions(1)}
        """.trimIndent()
    }

    // Helpers

    private fun extractAddedLines(fullContent: String, rawDiff: String?): String {
        // If we have a real diff, use it and filter to added lines
        if (!rawDiff.isNullOrBlank()) {
            return rawDiff.lines()
                .filter { it.startsWith("+") && !it.startsWith("+++") }
                .joinToString("\n")
        }
        // Fallback: prefix every line with + (treat entire file as new)
        return fullContent.lines()
            .joinToString("\n") { "+ $it" }
    }

    private fun buildJsonInstructions(fileCount: Int) = """
Respond with this exact JSON structure:
{
  "findings": [...],
  "analyzed_files": $fileCount
}
If no issues found: {"findings": [], "analyzed_files": $fileCount}
    """.trimIndent()
}

// ResponseParser

object ResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Greedy regex — extracts the outermost {...} block even if the model
    // wraps its answer in markdown or adds preamble text
    private val JSON_BLOCK_REGEX = Regex("""\{[\s\S]*\}""")

    fun parse(raw: String): AiScanResult {
        if (raw.isBlank()) {
            LOG.warn("SentinelAI: AI returned empty response")
            return AiScanResult.ParseError("(empty response)")
        }

        // Claude Haiku typically returns clean JSON, try direct parse first
        try {
            val parsed = json.decodeFromString<SecurityFindingsResponse>(raw)
            LOG.info("SentinelAI: parsed ${parsed.findings.size} finding(s) from Claude")
            return AiScanResult.Success(parsed.findings)
        } catch (_: Exception) {
            // Fallback: extract JSON block if Claude added any preamble
        }

        val jsonString = JSON_BLOCK_REGEX.find(raw)?.value
        if (jsonString == null) {
            LOG.warn("SentinelAI: No JSON block found in response:\n${raw.take(300)}")
            return AiScanResult.ParseError(raw)
        }

        return try {
            val parsed = json.decodeFromString<SecurityFindingsResponse>(jsonString)
            LOG.info("SentinelAI: parsed ${parsed.findings.size} finding(s) from Claude (extracted)")
            AiScanResult.Success(parsed.findings)
        } catch (e: Exception) {
            LOG.warn("SentinelAI: JSON parse failed — ${e.message}\nRaw:\n${raw.take(300)}")
            AiScanResult.ParseError(jsonString)
        }
    }
}

// Data carrier

data class FileWithContent(
    val relativePath: String,
    val absolutePath: String,
    val riskLevel: RiskLevel,
    val content: String,
    val rawDiff: String? = null // null - fall back to full-content diff
)

fun VirtualFile.toFileWithContent(projectBasePath: String): FileWithContent {
    val content = try { String(contentsToByteArray()) } catch (e: Exception) { "" }
    val relative = path.removePrefix(projectBasePath).trimStart('/', '\\')
    return FileWithContent(
        relativePath = relative,
        absolutePath = path,
        riskLevel = RiskMapEngine.classify(path),
        content = content
    )
}