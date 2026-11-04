package cam.su.kernel.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tells Home its root hiding card is stale when the hiding check page saved a scan or switched
 * a fix (Home otherwise reads it when it shows and on resume, not on the way back from that page).
 */
object HidingCheckSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun invalidate() = _version.update { it + 1 }
}
