package cam.su.kernel.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The "update available" notification was tapped: Home opens the changelog dialog once. */
object UpdateSignal {
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun request() {
        _showUpdateDialog.value = true
    }

    fun consume() {
        _showUpdateDialog.value = false
    }
}
