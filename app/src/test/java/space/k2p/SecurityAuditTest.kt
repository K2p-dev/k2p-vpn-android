package space.k2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.k2p.core.logging.AppLogger
import space.k2p.core.update.AppUpdateManager

class SecurityAuditTest {

    @Test
    fun redactMasksSensitiveFieldsAndVlessSecrets() {
        val sample = "vless://550e8400-e29b-41d4-a716-446655440000@example.com:443?pbk=PubKey12345&sid=ab12cd34&session_secret=supersecret123 \"publicKey\":\"PubKeyJson\" \"shortId\":\"SidJson\" Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
        val redacted = AppLogger.redact(sample)

        assertFalse(redacted.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(redacted.contains("PubKey12345"))
        assertFalse(redacted.contains("ab12cd34"))
        assertFalse(redacted.contains("supersecret123"))
        assertFalse(redacted.contains("PubKeyJson"))
        assertFalse(redacted.contains("SidJson"))
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(redacted.contains("***"))
    }

    @Test
    fun versionComparisonHandlesBetaAndFixSuffixes() {
        assertTrue(AppUpdateManager.isVersionNewer("0.6.7-beta", "0.6.6-beta-fix"))
        assertFalse(AppUpdateManager.isVersionNewer("0.6.6-beta-fix", "0.6.7-beta"))
        assertFalse(AppUpdateManager.isVersionNewer("0.6.7-beta", "0.6.7-beta"))
        assertTrue(AppUpdateManager.isVersionNewer("0.6.7-beta-fix", "0.6.7-beta"))
    }
}
