package cam.su.kernel.backup

import android.content.Context
import android.os.Build
import cam.su.kernel.BuildConfig
import cam.su.kernel.Cam
import cam.su.kernel.camApp
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.data.repository.SuperUserRepositoryImpl
import cam.su.kernel.files.Bookmarks
import cam.su.kernel.ui.util.getHideBootloaderStatus
import cam.su.kernel.ui.util.getSepolicy
import cam.su.kernel.ui.util.listModules
import cam.su.kernel.ui.util.setHideBootloader
import cam.su.kernel.ui.util.setSepolicy
import cam.su.kernel.ui.util.toggleModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** How a restore went: what was written, and what failed (profile keys, module ids). */
data class RestoreResult(
    val profiles: Int,
    val modules: Int,
    val bookmarks: Int,
    val failed: List<String>,
    /** module and SELinux hide changes take effect with the next boot */
    val rebootNeeded: Boolean,
)

/** Reads this phone's configuration into a [ConfigBackup], and writes one back (as root). */
object ConfigBackupStore {
    private const val PER_USER_RANGE = 100_000

    private val settings by lazy { SettingsRepositoryImpl() }
    private val filesPrefs get() = camApp.getSharedPreferences(Bookmarks.PREFS, Context.MODE_PRIVATE)

    suspend fun collect(): ConfigBackup = withContext(Dispatchers.IO) {
        val apps = SuperUserRepositoryImpl().getAppList().getOrThrow().first
        val profiles = apps.filter { it.allowSu || it.hasCustomProfile }.mapNotNull { app ->
            val profile = app.profile ?: return@mapNotNull null
            // camd keeps a custom root profile's rules; the kernel's profile does not carry them
            val rules = if (profile.allowSu && !profile.rootUseDefault) runCatching { getSepolicy(app.profileKey).trim() }.getOrDefault("") else ""
            SavedProfile(app.profileKey, app.uid / PER_USER_RANGE, profile, rules)
        }
        ConfigBackup(
            created = System.currentTimeMillis(),
            manager = BuildConfig.VERSION_NAME,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            features = SavedFeatures(
                suCompat = settings.getSuCompatModePref() != 2,
                kernelUmount = runCatching { settings.isKernelUmountEnabled() }.getOrNull(),
                selinuxHide = runCatching { settings.isSelinuxHideEnabled() }.getOrNull(),
                hideBootloader = runCatching { getHideBootloaderStatus() }.getOrNull()?.takeIf { it.props.isNotEmpty() || it.enabled }?.enabled,
                defaultUmountModules = runCatching { settings.isDefaultUmountModules() }.getOrNull(),
            ),
            profiles = profiles,
            modules = installedModules().map { (id, m) -> SavedModule(id, m.first, m.second, m.third) },
            bookmarks = Bookmarks.load(filesPrefs),
        )
    }

    suspend fun plan(backup: ConfigBackup): RestorePlan = withContext(Dispatchers.IO) {
        val apps = SuperUserRepositoryImpl().getAppList().getOrThrow().first
        val installed = apps.associate { (it.profileKey to it.uid / PER_USER_RANGE) to it.uid }
        val modules = installedModules().mapValues { it.value.third }
        RestorePlan.of(backup, installed, modules, Bookmarks.load(filesPrefs))
    }

    /** Writes the switches, then [plan]'s profiles, modules and bookmarks. */
    suspend fun restore(backup: ConfigBackup, plan: RestorePlan): RestoreResult = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        var rebootNeeded = false
        val f = backup.features
        f.suCompat?.let { on ->
            if (settings.setSuEnabled(on)) settings.setSuCompatModePref(if (on) 0 else 2)
        }
        f.kernelUmount?.let { settings.setKernelUmountEnabled(it) }
        f.selinuxHide?.let { on ->
            if (on != settings.isSelinuxHideEnabled()) rebootNeeded = settings.setSelinuxHideEnabled(on) != 0 || rebootNeeded
        }
        settings.execCamdFeatureSave()
        f.hideBootloader?.let { on -> if (on != getHideBootloaderStatus().enabled) setHideBootloader(on) }
        f.defaultUmountModules?.let { settings.setDefaultUmountModules(it) }

        var profiles = 0
        for (saved in plan.profiles) {
            // like the App Profile editor: a custom root profile's rules first, then the profile
            val rulesOk = saved.rules.isEmpty() || !saved.profile.allowSu || saved.profile.rootUseDefault || setSepolicy(saved.key, saved.rules)
            if (rulesOk && Cam.setAppProfile(saved.profile)) profiles++ else failed += saved.key
        }

        var modules = 0
        for (m in plan.modules) {
            if (toggleModule(m.id, m.enabled)) modules++ else failed += m.id
        }
        if (modules > 0) rebootNeeded = true

        if (plan.newBookmarks.isNotEmpty()) Bookmarks.save(filesPrefs, Bookmarks.load(filesPrefs) + plan.newBookmarks)

        RestoreResult(profiles, modules, plan.newBookmarks.size, failed, rebootNeeded)
    }

    /** Installed modules: id to (name, version, on). */
    private fun installedModules(): Map<String, Triple<String, String, Boolean>> = runCatching {
        val array = JSONArray(listModules())
        (0 until array.length()).associate { i ->
            val o = array.getJSONObject(i)
            o.getString("id") to Triple(o.optString("name"), o.optString("version"), o.optString("enabled") != "false")
        }
    }.getOrDefault(emptyMap())
}
