package cam.caesar.vn.attest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun aDeployedConfigMayBeTurnedOnWhateverWasPickedSince() {
        // after a restart nothing is picked yet; a bad pick later does not change what is deployed
        for (picked in KeyboxState.entries) {
            assertTrue(AttestUiState(configured = true, deployed = true, keybox = picked).canEnable)
        }
    }

    @Test
    fun nothingToTurnOnUntilConfiguredAndDeployed() {
        assertFalse(AttestUiState(configured = false, deployed = true, keybox = KeyboxState.VALID).canEnable)
        assertFalse(AttestUiState(configured = true, deployed = false, keybox = KeyboxState.VALID).canEnable)
    }
}
