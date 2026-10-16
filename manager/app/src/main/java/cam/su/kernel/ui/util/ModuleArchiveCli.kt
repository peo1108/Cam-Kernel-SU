package cam.su.kernel.ui.util

import cam.su.kernel.backup.ArchiveImport
import cam.su.kernel.backup.ArchiveModule
import cam.su.kernel.backup.ModuleArchive
import java.io.File

// Cam-only camd commands, kept out of CamCli.kt (upstream's KsuCli.kt) so upstream merges
// touch less of it. The modules backup; [file] is in the app's cache dir, so the app can read
// what camd writes there and camd (root) can read what the app copied there.

private fun camd(args: String): String {
    val result = getRootShell().newJob()
        .add("${getCamDaemonPath()} $args")
        .to(ArrayList(), ArrayList())
        .exec()
    if (!result.isSuccess) {
        val error = (result.err + result.out).lastOrNull { it.isNotBlank() }?.removePrefix("Error: ")
        error(error ?: "camd exited with ${result.code}")
    }
    // the JSON result is the last line camd prints
    return result.out.lastOrNull { it.trimStart().startsWith("{") } ?: error("camd printed no result")
}

private fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"

/** Writes every installed module into [file]. Blocking. */
fun exportModules(file: File): List<ArchiveModule> =
    ModuleArchive.parseModules(camd("module export ${quote(file.absolutePath)}"))

/** The modules in the backup [file], with what is installed now. Blocking. */
fun listModuleBackup(file: File): List<ArchiveModule> =
    ModuleArchive.parseModules(camd("module import ${quote(file.absolutePath)} --list"), moduleRiskScanner())

/** Stages [ids] from the backup [file]; they replace the installed ones on the next boot. Blocking. */
fun importModules(file: File, ids: List<String>): ArchiveImport {
    require(ids.isNotEmpty())
    return ModuleArchive.parseImport(camd("module import ${quote(file.absolutePath)} --only ${ids.joinToString(",")}"))
}
