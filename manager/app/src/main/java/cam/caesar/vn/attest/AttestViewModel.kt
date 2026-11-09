package cam.caesar.vn.attest

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.caesar.vn.camApp
import cam.caesar.vn.data.model.ATTESTATION_STATUS_URL
import cam.caesar.vn.data.model.parseRevocationList
import cam.caesar.vn.files.CamFileClient
import cam.caesar.vn.files.FileEntry
import cam.caesar.vn.files.FsException
import cam.caesar.vn.files.FsPath
import cam.caesar.vn.files.PickDirs
import cam.caesar.vn.files.pickEntries
import cam.caesar.vn.ui.util.checkAttestationReport
import cam.caesar.vn.ui.util.getFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val prefs = camApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        AttestUiState(browser = KeyboxBrowserUi(path = prefs.getString(KEY_PATH, null) ?: PickDirs.DOWNLOADS)),
    )
    val uiState: StateFlow<AttestUiState> = _uiState.asStateFlow()

    private var keyboxB64: String? = null
    private var importJob: Job? = null
    private var listJob: Job? = null

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

    /** What a system file picker gave, for what the in-app browser cannot reach (Drive, a USB stick). */
    fun importKeybox(uri: Uri) {
        importXml(path = null) {
            withContext(Dispatchers.IO) {
                // a provider (Drive) may answer slowly: both off the main thread
                val name = runCatching { uri.getFileName(camApp) }.getOrNull()
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                val text = camApp.contentResolver.openInputStream(uri)?.use { input ->
                    // one byte past the cap tells a file that is too big from one that fits
                    // (readNBytes needs API 33)
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (out.size() <= MAX_KEYBOX_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.takeIf { it.size() <= MAX_KEYBOX_BYTES }?.toString(Charsets.UTF_8.name())
                }
                name to text
            }
        }
    }

    /** A tap on a .xml in the in-app browser: read through the root file service, like a module zip. */
    fun importKeyboxFile(entry: FileEntry) {
        importXml(entry.path) {
            entry.name to String(CamFileClient.readBytes(entry.path, MAX_KEYBOX_BYTES), Charsets.UTF_8)
        }
    }

    /** [read] gives the file's name and its text (null when it cannot be read or is too big). */
    private fun importXml(path: String?, read: suspend () -> Pair<String?, String?>) {
        importJob?.cancel()
        _uiState.update { it.copy(readingKeybox = true, message = null) }
        importJob = viewModelScope.launch {
            var name: String? = null
            val xml = try {
                read().let { (n, text) -> name = n; text }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a provider or the root service can throw nearly anything: the card says it is invalid
                Log.w(TAG, "cannot read keybox", e)
                null
            }
            val (state, b64) = withContext(Dispatchers.IO) {
                val keybox = xml?.let { parseKeybox(it) }
                if (keybox == null) {
                    KeyboxState.INVALID to null
                } else {
                    keyboxStateOf(true, keybox.statusAgainst(fetchRevocation())) to keybox.toB64()
                }
            }
            keyboxB64 = b64
            _uiState.update {
                it.copy(
                    keybox = state,
                    keyboxName = name,
                    keyboxPath = path,
                    readingKeybox = false,
                    message = null,
                    // a usable keybox closes the browser; a bad one leaves it open to pick another
                    browser = if (state == KeyboxState.INVALID) it.browser else it.browser.copy(open = false),
                )
            }
        }
    }

    fun openBrowser() {
        _uiState.update { it.copy(browser = it.browser.copy(open = true)) }
        list()
    }

    fun closeBrowser() {
        listJob?.cancel()
        _uiState.update { it.copy(browser = it.browser.copy(open = false, listing = false)) }
    }

    fun navigate(path: String) {
        _uiState.update { it.copy(browser = it.browser.copy(path = path)) }
        prefs.edit { putString(KEY_PATH, path) }
        list()
    }

    fun up() {
        val path = _uiState.value.browser.path
        if (path != "/") navigate(FsPath.parent(path))
    }

    private fun list() {
        val path = _uiState.value.browser.path
        listJob?.cancel()
        _uiState.update { it.copy(browser = it.browser.copy(listing = true)) }
        listJob = viewModelScope.launch {
            val result = try {
                Result.success(CamFileClient.listDir(path))
            } catch (e: FsException) {
                Result.failure(e)
            }
            val entries = withContext(Dispatchers.Default) { pickEntries(result.getOrDefault(emptyList()), ".xml") }
            _uiState.update { state ->
                if (state.browser.path != path) return@update state
                state.copy(
                    browser = state.browser.copy(
                        entries = entries,
                        listing = false,
                        errno = (result.exceptionOrNull() as? FsException)?.errno,
                    ),
                )
            }
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

    private companion object {
        const val TAG = "Attest"
        const val PREFS = "attest_keybox"
        const val KEY_PATH = "path"

        // a keybox is a few KB; anything this big is not one
        const val MAX_KEYBOX_BYTES = 1 shl 20
    }
}
