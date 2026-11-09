package cam.caesar.vn.attest

import cam.caesar.vn.files.FileEntry
import cam.caesar.vn.files.PickDirs
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

/** The in-app keybox picker (the module install pane's browser), read through the root file service. */
data class KeyboxBrowserUi(
    val open: Boolean = false,
    val path: String = PickDirs.DOWNLOADS,
    val entries: List<FileEntry> = emptyList(),
    val listing: Boolean = false,
    val errno: Int? = null,
)

data class AttestUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val deployed: Boolean = false,
    val configured: Boolean = false,
    val keybox: KeyboxState = KeyboxState.NONE,
    /** the imported file's name, shown beside its standing */
    val keyboxName: String? = null,
    /** where it came from in the browser, marked there */
    val keyboxPath: String? = null,
    /** a keybox is being read and checked against Google's revocation list */
    val readingKeybox: Boolean = false,
    val browser: KeyboxBrowserUi = KeyboxBrowserUi(),
    val packages: List<String> = DEFAULT_TARGETS,
    val attestationReport: AttestationReport? = null,
    val checkingAttestation: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    /**
     * The feature is usable once configured and deployed. Not the keybox picked on this screen: the
     * device keeps the config, whose keybox was usable when it was written (configure needs that),
     * so after a restart (nothing picked yet) it may still be turned on, and a bad pick afterwards
     * does not change what is deployed.
     */
    val canEnable: Boolean get() = configured && deployed

    companion object {
        val DEFAULT_TARGETS = listOf("com.google.android.gms", "com.android.vending")
    }
}
