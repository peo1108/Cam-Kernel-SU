package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class ModuleUpdateInfo(
    val downloadUrl: String,
    val version: String,
    val changelog: String,
    /** found in Cam's module library rather than the module's own updateJson */
    val fromLibrary: Boolean = false,
) {
    companion object {
        val Empty = ModuleUpdateInfo("", "", "")
    }
}

/**
 * A newer release of an installed module in Cam's module library, offered the same way as an
 * updateJson update. Disabled modules and modules with a change pending (update, removal) get
 * none, as with updateJson. The library lists a versionCode only for some modules; for the rest
 * the version names are compared ([compareVersionNames]).
 */
fun libraryUpdate(module: Module, library: List<RepoModule>): ModuleUpdateInfo? {
    if (module.remove || module.update || !module.enabled) return null
    val release = library.firstOrNull { it.moduleId == module.id } ?: return null
    val asset = release.latestAsset ?: return null
    if (asset.downloadUrl.isBlank()) return null
    val newer = if (release.latestVersionCode > 0) {
        release.latestVersionCode > module.versionCode
    } else {
        (compareVersionNames(release.latestRelease, module.version) ?: 0) > 0
    }
    if (!newer) return null
    return ModuleUpdateInfo(
        downloadUrl = asset.downloadUrl,
        version = release.latestRelease,
        changelog = "",
        fromLibrary = true,
    )
}

/**
 * Compares two version names by their runs of digits: "v4.0.11" is newer than "4.0.10 (930)", and a
 * name whose numbers are a prefix of the other's ("4.0.11" against "4.0.11 (930)") is the older
 * one. Null when either has no digits, so nothing is offered on a name that cannot be read.
 */
fun compareVersionNames(a: String, b: String): Int? {
    fun parts(name: String) = Regex("[0-9]+").findAll(name).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
    val left = parts(a)
    val right = parts(b)
    if (left.isEmpty() || right.isEmpty()) return null
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        // digits only: a longer run is a bigger number, same length compares as text
        val diff = if (x.length != y.length) x.length - y.length else x.compareTo(y)
        if (diff != 0) return diff.coerceIn(-1, 1)
    }
    return (left.size - right.size).coerceIn(-1, 1)
}
