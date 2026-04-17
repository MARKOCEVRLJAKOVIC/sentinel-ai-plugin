package dev.marko.sentinelai.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [RiskMapEngine] — file risk classification.
 *
 * Verifies that files are classified into the correct [RiskLevel]
 * based on their path patterns.
 */
class RiskMapEngineTest {

    // ── CRITICAL ─────────────────────────────────────────────────────────────

    @Test
    fun `classifies dot-env file as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify(".env"))
    }

    @Test
    fun `classifies dot-env-production file as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify(".env.production"))
    }

    @Test
    fun `classifies pem file as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify("certs/server.pem"))
    }

    @Test
    fun `classifies key file as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify("ssl/private.key"))
    }

    @Test
    fun `classifies p12 keystore as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify("keystore.p12"))
    }

    @Test
    fun `classifies jks keystore as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify("truststore.jks"))
    }

    @Test
    fun `classifies secrets file as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify("config/secrets.yml"))
    }

    @Test
    fun `classifies credentials file as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify("aws/credentials.json"))
    }

    @Test
    fun `classifies idea dataSources as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify(".idea/dataSources.xml"))
    }

    @Test
    fun `classifies idea workspace as CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskMapEngine.classify(".idea/workspace.xml"))
    }

    // ── HIGH ─────────────────────────────────────────────────────────────────

    @Test
    fun `classifies application-yml as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("src/main/resources/application.yml"))
    }

    @Test
    fun `classifies application-prod-properties as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("src/main/resources/application-prod.properties"))
    }

    @Test
    fun `classifies Dockerfile as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("Dockerfile"))
    }

    @Test
    fun `classifies docker-compose as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("docker-compose.yml"))
    }

    @Test
    fun `classifies logback-xml as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("src/main/resources/logback.xml"))
    }

    @Test
    fun `classifies hibernate config as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("hibernate.cfg.xml"))
    }

    @Test
    fun `classifies terraform file as HIGH`() {
        assertEquals(RiskLevel.HIGH, RiskMapEngine.classify("infra/terraform/main.tf"))
    }

    // ── MEDIUM ───────────────────────────────────────────────────────────────

    @Test
    fun `classifies Kotlin service file as MEDIUM`() {
        assertEquals(RiskLevel.MEDIUM, RiskMapEngine.classify("src/main/kotlin/UserService.kt"))
    }

    @Test
    fun `classifies Java controller as MEDIUM`() {
        assertEquals(RiskLevel.MEDIUM, RiskMapEngine.classify("src/main/java/AuthController.java"))
    }

    @Test
    fun `classifies plain Kotlin file as MEDIUM`() {
        assertEquals(RiskLevel.MEDIUM, RiskMapEngine.classify("src/main/kotlin/Utils.kt"))
    }

    @Test
    fun `classifies plain Java file as MEDIUM`() {
        assertEquals(RiskLevel.MEDIUM, RiskMapEngine.classify("src/main/java/Main.java"))
    }

    // ── LOW ──────────────────────────────────────────────────────────────────

    @Test
    fun `classifies markdown as LOW`() {
        assertEquals(RiskLevel.LOW, RiskMapEngine.classify("README.md"))
    }

    @Test
    fun `classifies CSS as LOW`() {
        assertEquals(RiskLevel.LOW, RiskMapEngine.classify("style.css"))
    }

    @Test
    fun `classifies image as LOW`() {
        assertEquals(RiskLevel.LOW, RiskMapEngine.classify("logo.png"))
    }

    @Test
    fun `classifies text file as LOW`() {
        assertEquals(RiskLevel.LOW, RiskMapEngine.classify("notes.txt"))
    }
}
