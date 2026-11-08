package cam.caesar.vn.attest

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.caesar.vn.camApp
import cam.caesar.vn.data.model.ATTESTATION_STATUS_URL
import cam.caesar.vn.data.model.parseRevocationList
import cam.caesar.vn.ui.util.checkAttestationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Drives the attestation screen: load the feature state, import + revocation-check a keybox, harvest
 * and resolve the config through the root service, toggle the feature, and run the attestation check.
 * Harvest/resolve/keybox parsing are pure ([Harvester], [Resolver], [KeyboxStore]); the device calls
 * go through [AttestRepository].
 */
class AttestViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AttestUiState())
    val uiState: StateFlow<AttestUiState> = _uiState.asStateFlow()

    private var keyboxB64: String? = null

    fun load() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { AttestRepository.status() }
            _uiState.update {
                it.copy(
                    loading = false,
                    enabled = status.enabled,
                    deployed = status.deployed,
                    configured = status.pushConfigured,
                )
            }
        }
    }

    fun importKeybox(uri: Uri) {
        viewModelScope.launch {
            val (state, b64) = withContext(Dispatchers.IO) {
                val xml = runCatching {
                    camApp.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val keybox = xml?.let { parseKeybox(it) }
                if (keybox == null) {
                    KeyboxState.INVALID to null
                } else {
                    keyboxStateOf(true, keybox.statusAgainst(fetchRevocation())) to keybox.toB64()
                }
            }
            keyboxB64 = b64
            _uiState.update { it.copy(keybox = state, message = null) }
        }
    }

    fun configure() {
        val kb = keyboxB64 ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            val ok = withContext(Dispatchers.IO) {
                val harvest = harvest() ?: return@withContext false
                val profiles = listOf(AttestProfile("default", kb, securityLevel = 1, _uiState.value.packages))
                AttestRepository.configure(harvest, profiles) { pkg -> uidsOf(pkg) }
            }
            val status = withContext(Dispatchers.IO) { AttestRepository.status() }
            _uiState.update {
                it.copy(
                    busy = false,
                    configured = status.pushConfigured,
                    deployed = status.deployed,
                    message = if (ok) "ok" else "fail",
                )
            }
        }
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AttestRepository.enable(on) }
            _uiState.update { it.copy(enabled = on) }
        }
    }

    fun checkAttestation() {
        viewModelScope.launch {
            _uiState.update { it.copy(checkingAttestation = true) }
            val report = withContext(Dispatchers.IO) { checkAttestationReport() }
            _uiState.update { it.copy(checkingAttestation = false, attestationReport = report) }
        }
    }

    private fun uidsOf(pkg: String): List<Pair<Int, Int>> = runCatching {
        listOf(camApp.packageManager.getPackageUid(pkg, 0) to 0)
    }.getOrElse { emptyList() }

    private fun fetchRevocation(): Map<String, String>? = runCatching {
        camApp.okhttpClient.newCall(Request.Builder().url(ATTESTATION_STATUS_URL).build()).execute().use { resp ->
            if (resp.isSuccessful) parseRevocationList(resp.body.string()) else null
        }
    }.getOrNull()
}
