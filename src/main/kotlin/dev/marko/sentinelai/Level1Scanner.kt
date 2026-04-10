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
            Regex("""(?i)(api_?key|apikey|api-key)\s*[:=]\s*["']?(?!.*(\$\{|getenv|placeholder|example|todo|mock|your-))[a-zA-Z0-9\-_]{16,}["']?"""),
            "Hardcoded API key detected"
        ),
        Pattern(
            Regex("""(?i)(password|passwd|pwd)\s*[:=]\s*["']?(?!.*(\$\{|getenv|placeholder|example|todo|mock|your-))[^\s"']{6,}["']?"""),
            "Hardcoded password detected"
        ),
        Pattern(
            Regex("""(?i)(secret|client_?secret)\s*[:=]\s*["']?(?!.*(\$\{|getenv|placeholder|example|todo|mock|your-))[a-zA-Z0-9\-_]{8,}["']?"""),
            "Hardcoded secret detected"
        ),
        Pattern(
            Regex("""(?i)(token|auth_?token|access_?token)\s*[:=]\s*["']?(?!.*(\$\{|getenv|placeholder|example|todo|mock|your-))[a-zA-Z0-9\-_.]{16,}["']?"""),
            "Hardcoded token detected"
        ),

        Pattern(
            Regex("""sk-[a-zA-Z0-9]{20,}"""),
            "OpenAI API key detected"
        ),
        Pattern(
            Regex("""sk-proj-[a-zA-Z0-9\-_]{20,}"""),
            "OpenAI project API key detected"
        ),
        Pattern(
            Regex("""ghp_[a-zA-Z0-9]{36,}"""),
            "GitHub personal access token detected"
        ),
        Pattern(
            Regex("""ghs_[a-zA-Z0-9]{36,}"""),
            "GitHub app secret detected"
        ),
        Pattern(
            Regex("""(?i)bearer\s+[a-zA-Z0-9\-_.]{20,}"""),
            "Bearer token hardcoded in source"
        ),
        Pattern(
            Regex("""AIza[0-9A-Za-z\-_]{35}"""),
            "Google API key detected"
        ),
        Pattern(
            Regex("""AKIA[0-9A-Z]{16}"""),
            "AWS Access Key ID detected"
        ),
        Pattern(
            Regex("""(?i)aws_secret_access_key\s*[:=]\s*["']?[a-zA-Z0-9/+]{40}["']?"""),
            "AWS Secret Access Key detected"
        ),

        Pattern(
            Regex("""(?i)["']?(api_?key|password|secret|token)["']?\s*:\s*["'](?!.*(\$\{|getenv|placeholder|example|todo|mock|your-))[a-zA-Z0-9\-_]{8,}["']"""),
            "Hardcoded secret in JSON/YAML value"
        ),

        Pattern(
            Regex("""(?i)jdbc:[a-z]+://[^:@\s]+:[^@\s]{3,}@[^\s]+"""),
            "JDBC connection string with credentials"
        ),
        Pattern(
            Regex("""(?i)[?&](password|passwd|pwd)=[^&\s"']{3,}"""),
            "Password in URL query parameter"
        ),
        Pattern(
            Regex("""(?i)(user|username)=[^&\s"']{3,}&(password|passwd|pwd)=[^&\s"']{3,}"""),
            "Credentials in URL query parameters"
        ),

        Pattern(
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
            "Private key detected"
        ),
        Pattern(
            Regex("""-----BEGIN CERTIFICATE-----"""),
            "Certificate embedded in source"
        ),

        Pattern(
            Regex("""(?<![/\w])(val|var|const|private|public|static|final)\s+\w+\s*[:=]+\s*["'][a-zA-Z0-9+/=\-_]{32,}["']"""),
            "High-entropy string assigned to variable (possible hardcoded secret)"
        )
    )

    fun scan(filePath: String, content: String): List<ScanFinding> {
        val findings = mutableListOf<ScanFinding>()

        content.lines().forEachIndexed { index, line ->
            if (isComment(line) || isSafe(line)) return@forEachIndexed

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
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("#") ||
                t.startsWith("*")  || t.startsWith("/*")
    }

    private fun isSafe(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("system.getenv") ||
                lower.contains("\${")           ||
                lower.contains("process.env")   ||
                lower.contains("your-api-key")  ||
                lower.contains("placeholder")   ||
                lower.contains("todo")          ||
                lower.contains("example")       ||
                lower.contains("mock")          ||
                lower.contains("<your")         ||
                lower.contains("test-")         ||
                lower.contains("fake-")
    }
}