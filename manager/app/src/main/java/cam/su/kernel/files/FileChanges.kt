package cam.su.kernel.files

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Files changed by a screen other than the Files tab (the editor saved one): the tab reloads
 * its lists. Coming back from the editor is a pop inside the activity, not a resume.
 */
object FileChanges {
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    fun notify(path: String) {
        _changed.tryEmit(path)
    }
}
