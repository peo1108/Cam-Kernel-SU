package cam.su.kernel.ui.viewmodel

import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.PanelNav
import cam.su.kernel.files.SortOptions
import java.io.File

data class PanelUi(
    val nav: PanelNav,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** errno of the last failed listing; the list then stays empty. */
    val errorErrno: Int? = null,
    val selection: Set<String> = emptySet(),
    /** [total, available] bytes of the file system the folder is on. */
    val disk: LongArray? = null,
    /** The folder [entries] were listed from; it trails [nav] while the next one loads. */
    val loadedPath: String? = null,
) {
    // counted once per state, not on every recomposition of the header
    val folders by lazy(LazyThreadSafetyMode.PUBLICATION) { entries.count { it.isDir } }
    val files get() = entries.size - folders
}

data class OpUi(
    val kind: OpKind,
    val done: Long = 0,
    val total: Long = 0,
    val current: String = "",
    val conflict: CamFileClient.OpEvent.Conflict? = null,
)

/** MD5, SHA-1, SHA-256 of [entry]; null while computing. [token] tells one opening from the next. */
data class ChecksumUi(val entry: FileEntry, val token: Int, val values: List<String>? = null)

/** What an APK says about itself, read from a cached copy. */
data class ApkInfoUi(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val signer: String?,
    val permissions: List<String>,
    val icon: android.graphics.drawable.Drawable?,
)

/** The systemless override dialog for one system file. */
data class OverrideUi(
    val entry: FileEntry,
    val target: String,
    val exists: Boolean,
    val metamodule: Boolean,
    /** a file with the same name in the other panel, offered as the replacement */
    val replacement: FileEntry?,
)

/**
 * The partition list: to back one up ([image] null), or to pick where [image] gets flashed.
 * [all] shows every partition, not just the boot chain.
 */
data class PartitionsUi(
    val list: List<cam.su.kernel.files.Partition>,
    val slot: String,
    val image: FileEntry? = null,
    val all: Boolean = false,
)

data class SearchUi(val root: String, val running: Boolean = true, val results: List<FileEntry> = emptyList())

/** The Properties dialog: [measure] fills in after the tree was walked. [token] tells one opening from the next. */
data class PropertiesUi(val entry: FileEntry, val token: Int = 0, val measure: LongArray? = null)

sealed interface FilesMessage {
    data class Finished(val kind: OpKind, val items: Int, val skipped: Int) : FilesMessage
    data object Cancelled : FilesMessage
    data class Failed(val errno: Int, val detail: String?) : FilesMessage
    data object Done : FilesMessage
    data class Text(val text: String) : FilesMessage

    /** Another copy/move/delete is still running: one at a time. */
    data object Busy : FilesMessage
}

/** One-shot work for the screen: hand a cached copy of a root file to another app. */
sealed interface FilesEvent {
    data class Open(val uri: android.net.Uri, val mime: String) : FilesEvent
    data class Share(val uris: List<android.net.Uri>) : FilesEvent
    /** A module zip to hand to the Flash screen. */
    data class Flash(val file: File) : FilesEvent
    data class Edit(val path: String) : FilesEvent
    data class Hex(val path: String) : FilesEvent
    data class Compare(val left: String, val right: String) : FilesEvent
    data class Run(val path: String) : FilesEvent
    data class Terminal(val cwd: String) : FilesEvent
}

data class FilesUiState(
    val panels: List<PanelUi>,
    val active: Int = 0,
    val sort: SortOptions = SortOptions(),
    val op: OpUi? = null,
    val message: FilesMessage? = null,
    val properties: PropertiesUi? = null,
    val bookmarks: List<String> = emptyList(),
    val busy: Boolean = false,
    val checksum: ChecksumUi? = null,
    val apkInfo: ApkInfoUi? = null,
    val search: SearchUi? = null,
    val override: OverrideUi? = null,
    /** running apps offered by "Browse as an app": (pid, uid, process) */
    val processes: List<Triple<Int, Int, String>>? = null,
    /** pid → process name of the apps browsed as */
    val appNames: Map<Int, String> = emptyMap(),
    val partitions: PartitionsUi? = null,
    /** The trash dialog's list; null while it is closed. */
    val trash: List<cam.su.kernel.files.TrashItem>? = null,
) {
    val activePanel get() = panels[active]
    val otherPanel get() = panels[1 - active]
}
