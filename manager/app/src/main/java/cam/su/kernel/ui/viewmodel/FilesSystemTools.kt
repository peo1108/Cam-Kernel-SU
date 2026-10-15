package cam.su.kernel.ui.viewmodel

import cam.su.kernel.files.AppView
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.Errno
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileOp
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.Systemless
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Files tab's system tools: systemless overrides, browsing as an app, partitions. */
class FilesSystemTools internal constructor(private val host: FilesHost) {
    private val state get() = host.state

    private fun fail(e: FsException) = state.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }

    // --- systemless overrides (needs a metamodule to mount modules) ---

    fun showOverride(entry: FileEntry) {
        host.scope.launch {
            try {
                val target = Systemless.target(entry.path)
                val other = state.value.otherPanel.entries.firstOrNull { it.name == entry.name && !it.isDir && it.path != entry.path }
                val ui = OverrideUi(
                    entry = entry,
                    target = target,
                    exists = CamFileClient.stat(target) != null,
                    metamodule = CamFileClient.stat(Systemless.METAMODULE_LINK) != null,
                    replacement = other,
                )
                state.update { it.copy(override = ui) }
            } catch (e: FsException) {
                fail(e)
            }
        }
    }

    fun hideOverride() = state.update { it.copy(override = null) }

    /** Copies the system file into the module (once) and opens the copy in the editor. */
    fun overrideEdit() {
        val o = state.value.override ?: return
        hideOverride()
        host.scope.launch {
            try {
                prepareOverride(o.target)
                if (o.exists) {
                    host.send(FilesEvent.Edit(o.target))
                } else {
                    host.runOp(FileOp(OpKind.COPY, listOf(o.entry.path), FsPath.parent(o.target))) {
                        host.scope.launch { host.send(FilesEvent.Edit(o.target)) }
                    }
                }
            } catch (e: FsException) {
                fail(e)
            }
        }
    }

    /** Puts the same-named file from the other panel in place of the system one. */
    fun overrideReplace() {
        val o = state.value.override ?: return
        val replacement = o.replacement ?: return
        hideOverride()
        host.scope.launch {
            try {
                prepareOverride(o.target)
                host.runOp(FileOp(OpKind.COPY, listOf(replacement.path), FsPath.parent(o.target), preserve = false))
            } catch (e: FsException) {
                fail(e)
            }
        }
    }

    fun overrideRemove() {
        val o = state.value.override ?: return
        hideOverride()
        host.runOp(FileOp(OpKind.DELETE, listOf(o.target)))
    }

    private suspend fun prepareOverride(target: String) {
        mkdirs(FsPath.parent(target))
        val prop = FsPath.child(Systemless.MODULE, "module.prop")
        if (CamFileClient.stat(prop) == null) CamFileClient.write(prop) { it.write(Systemless.MODULE_PROP.toByteArray()) }
    }

    private suspend fun mkdirs(path: String) {
        if (path == "/" || CamFileClient.stat(path) != null) return
        mkdirs(FsPath.parent(path))
        CamFileClient.mkdir(path)
    }

    // --- browse as an app (/proc/<pid>/root) ---

    fun chooseApp() {
        host.scope.launch {
            try {
                state.update { it.copy(processes = CamFileClient.appProcesses()) }
            } catch (e: FsException) {
                fail(e)
            }
        }
    }

    fun hideProcesses() = state.update { it.copy(processes = null) }

    fun viewAsApp(pid: Int, name: String) {
        state.update { it.copy(processes = null, appNames = it.appNames + (pid to name)) }
        host.navigateActive(AppView.root(pid))
    }

    // --- partitions: back up any, flash the boot chain ---

    /** The partition list; with [image], to choose where it gets flashed. */
    fun showPartitions(image: FileEntry? = null) {
        state.update { it.copy(busy = true) }
        host.scope.launch {
            try {
                val (list, slot) = CamFileClient.partitions()
                if (list.isEmpty()) throw FsException(Errno.ENOENT, detail = "/dev/block/by-name")
                state.update { it.copy(partitions = PartitionsUi(cam.su.kernel.files.Partitions.ordered(list, slot), slot, image)) }
            } catch (e: FsException) {
                fail(e)
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun showAllPartitions() = state.update { it.copy(partitions = it.partitions?.copy(all = true)) }

    fun hidePartitions() = state.update { it.copy(partitions = null) }

    /** Into /sdcard/CamBackup/<name>_<time>.img; the active panel opens there when it is done. */
    fun backupPartition(name: String) {
        hidePartitions()
        val dest = cam.su.kernel.files.Partitions.backupPath(name, System.currentTimeMillis())
        host.scope.launch {
            try {
                mkdirs(FsPath.parent(dest))
                host.runOp(FileOp(OpKind.BACKUP, listOf(name), dest)) { host.navigateActive(FsPath.parent(dest)) }
            } catch (e: FsException) {
                fail(e)
            }
        }
    }

    /** What the flash dialog shows before it lets the flash go. */
    data class FlashCheck(val problem: cam.su.kernel.files.ImageProblem?, val room: Boolean)

    /** Reads the image header and the free space; the root side checks both again before writing. */
    suspend fun checkFlash(image: FileEntry, partition: cam.su.kernel.files.Partition): FlashCheck {
        val header = runCatching { CamFileClient.readRange(image.path, 0, cam.su.kernel.files.Partitions.HEADER_SIZE) }.getOrNull()
        val free = runCatching { CamFileClient.statFs(FsPath.parent(cam.su.kernel.files.Partitions.BACKUP_DIR))[1] }.getOrNull()
        return FlashCheck(
            problem = header?.let { cam.su.kernel.files.Partitions.checkImage(partition.name, it) },
            room = free == null || free >= cam.su.kernel.files.Partitions.backupRoom(partition.size),
        )
    }

    /**
     * The partition as it is goes to /sdcard/CamBackup first (the root side refuses to flash
     * without that path); the active panel opens there when it is done.
     */
    fun flashPartition(image: FileEntry, name: String) {
        hidePartitions()
        val backup = cam.su.kernel.files.Partitions.backupPath(name, System.currentTimeMillis())
        host.scope.launch {
            try {
                mkdirs(FsPath.parent(backup))
                host.runOp(FileOp(OpKind.FLASH, listOf(image.path, backup), name)) { host.navigateActive(FsPath.parent(backup)) }
            } catch (e: FsException) {
                fail(e)
            }
        }
    }
}
