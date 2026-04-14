package dev.marko.sentinelai.scan

object RiskMapEngine {

    private val CRITICAL_PATTERNS = listOf(
        Regex("""\.idea/dataSources.*"""),
        Regex("""\.idea/workspace\.xml"""),
        Regex(""".*\.env(\..+)?$"""),
        Regex(""".*secrets.*"""),
        Regex(""".*credentials.*"""),
        Regex(""".*\.(pem|key|p12|jks)$""")
    )

    private val HIGH_PATTERNS = listOf(
        Regex(""".*application(-\w+)?\.yml$"""),
        Regex(""".*application(-\w+)?\.properties$"""),
        Regex(""".*logback\.xml$"""),
        Regex(""".*hibernate\.cfg\.xml$"""),
        Regex("""(Dockerfile|docker-compose\.yml)$"""),
        Regex(""".*terraform.*""")
    )

    private val MEDIUM_PATTERNS = listOf(
        Regex(""".*\w*(Service|Auth|Security|Controller)\w*\.(kt|java)$"""),
        Regex(""".*\.(kt|java)$""")
    )

    fun classify(filePath: String): RiskLevel {
        if (CRITICAL_PATTERNS.any { it.containsMatchIn(filePath) }) return RiskLevel.CRITICAL
        if (HIGH_PATTERNS.any { it.containsMatchIn(filePath) }) return RiskLevel.HIGH
        if (MEDIUM_PATTERNS.any { it.containsMatchIn(filePath) }) return RiskLevel.MEDIUM
        return RiskLevel.LOW
    }
}