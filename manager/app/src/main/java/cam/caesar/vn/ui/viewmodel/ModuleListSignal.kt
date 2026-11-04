package cam.su.kernel.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tells the Module page its list is stale when another page changed module state or the
 * conflict options (the Module page otherwise loads once and on pull-to-refresh).
 */
object ModuleListSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun invalidate() = _version.update { it + 1 }
}
