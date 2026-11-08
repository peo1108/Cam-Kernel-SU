package cam.caesar.vn.attest

import cam.caesar.vn.ui.util.AttestationReport

/** The imported keybox's standing for the UI. REVOKED and INVALID are shown differently (warning vs error). */
enum class KeyboxState { NONE, VALID, REVOKED, INVALID }

/** Map a keybox import outcome to its UI state. Pure. [parsed] false means the XML was unreadable. */
fun keyboxStateOf(parsed: Boolean, status: KeyboxStatus?): KeyboxState = when {
    !parsed -> KeyboxState.INVALID
    status is KeyboxStatus.Revoked -> KeyboxState.REVOKED
    status is KeyboxStatus.Invalid -> KeyboxState.INVALID
    else -> KeyboxState.VALID
}

data class AttestUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val deployed: Boolean = false,
    val configured: Boolean = false,
    val keybox: KeyboxState = KeyboxState.NONE,
    val packages: List<String> = DEFAULT_TARGETS,
    val attestationReport: AttestationReport? = null,
    val checkingAttestation: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    /** The feature is usable only once configured, deployed and a keybox has loaded. */
    val canEnable: Boolean get() = configured && deployed && keybox != KeyboxState.NONE

    companion object {
        val DEFAULT_TARGETS = listOf("com.google.android.gms", "com.android.vending")
    }
}
