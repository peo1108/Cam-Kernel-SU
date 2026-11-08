package cam.caesar.vn.attest

import org.junit.Assert.assertEquals
import org.junit.Test

class AttestViewModelTest {

    @Test
    fun unreadableKeyboxIsInvalid() {
        assertEquals(KeyboxState.INVALID, keyboxStateOf(parsed = false, status = null))
    }

    @Test
    fun validKeyboxIsValid() {
        assertEquals(KeyboxState.VALID, keyboxStateOf(parsed = true, status = KeyboxStatus.Valid))
    }

    @Test
    fun revokedKeyboxWarnsButStillLoads() {
        // Review Focus: a revoked keybox warns but the user may still proceed (it is their keybox).
        assertEquals(
            KeyboxState.REVOKED,
            keyboxStateOf(parsed = true, status = KeyboxStatus.Revoked(emptyList())),
        )
    }
}
