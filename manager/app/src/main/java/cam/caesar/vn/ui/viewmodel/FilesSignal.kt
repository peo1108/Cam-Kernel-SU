package cam.caesar.vn.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks the Files tab to open one file, from outside it (another app's "Open with",
 * [cam.caesar.vn.files.IncomingFile]). The main screen turns to the tab; the tab goes to the
 * file's folder, opens the file the way tapping it would and clears the request.
 */
object FilesSignal {
    private val _openRequest = MutableStateFlow<String?>(null)
    val openRequest: StateFlow<String?> = _openRequest.asStateFlow()

    fun open(path: String) {
        _openRequest.value = path
    }

    fun consume() {
        _openRequest.value = null
    }
}
