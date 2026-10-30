package cam.su.kernel.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks the Superuser page to open one uid's profile, from a page on top of it (an su log event's
 * "Open profile"). The page picks the app, scrolls to its row and clears the request.
 */
object SuperUserSignal {
    private val _profileRequest = MutableStateFlow<Int?>(null)
    val profileRequest: StateFlow<Int?> = _profileRequest.asStateFlow()

    fun openProfile(uid: Int) {
        _profileRequest.value = uid
    }

    fun consume() {
        _profileRequest.value = null
    }
}
