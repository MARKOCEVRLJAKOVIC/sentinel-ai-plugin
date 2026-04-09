package dev.marko.sentinelai

data class ScanFinding(
    val file: String,
    val line: Int,
    val description: String,
    val snippet: String
)

object Level1Scanner {

    private data class Pattern(val regex: Regex, val description: String)

    private val PATTERNS = listOf(
        Pattern(
            Regex("""(?i)(api_key|apikey|api-key)\s*=\s*["']?[\w\-]{10,}["']?"""),
            "Hardcoded API key detected"
        ),
        Pattern(
            Regex("""(?i)(password|passwd|pwd)\s*=\s*["'][^$\{][^"']{3,}["']"""),
            "Hardcoded password detected"
        ),
        Pattern(
            Regex("""(?i)(secret|token)\s*=\s*["'][\w\-]{8,}["']"""),
            "Hardcoded secret/token detected"
        ),
        Pattern(
            Regex("""sk-[a-zA-Z0-9]{20,}"""),
            "OpenAI API key detected"
        ),
        Pattern(
            Regex("""(?i)jdbc:[a-z]+://[^:]+:[^@]+@"""),
            "Database connection string with credentials"
        ),
        Pattern(
            Regex("""-----BEGIN (RSA |EC )?PRIVATE KEY-----"""),
            "Private key detected"
        )
    )

    fun scan(filePath: String, content: String): List<ScanFinding> {
        val findings = mutableListOf<ScanFinding>()

        content.lines().forEachIndexed { index, line ->
            if (isComment(line) || isPlaceholder(line)) return@forEachIndexed

            PATTERNS.forEach { pattern ->
                if (pattern.regex.containsMatchIn(line)) {
                    findings.add(
                        ScanFinding(
                            file = filePath,
                            line = index + 1,
                            description = pattern.description,
                            snippet = line.trim().take(80)
                        )
                    )
                }
            }
        }

        return findings
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") ||
                trimmed.startsWith("#") ||
                trimmed.startsWith("*") ||
                trimmed.startsWith("/*")
    }

    private fun isPlaceholder(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("your-api-key") ||
                lower.contains("todo") ||
                lower.contains("placeholder") ||
                lower.contains("example") ||
                lower.contains("system.getenv") ||
                lower.contains("\${") ||  // env var reference
                lower.contains("mock")
    }
}