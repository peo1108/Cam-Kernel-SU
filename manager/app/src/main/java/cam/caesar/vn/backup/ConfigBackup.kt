package cam.su.kernel.backup

import cam.su.kernel.CamNative
import org.json.JSONArray
import org.json.JSONObject

/** One app's profile, kept by profile key (the package name) and Android user: uids differ from phone to phone. */
data class SavedProfile(
    val key: String,
    val userId: Int,
    val profile: CamNative.Profile,
    /** sepolicy rules of a custom root profile, which camd keeps apart from the kernel's profile */
    val rules: String = "",
)

data class SavedModule(val id: String, val name: String, val version: String, val enabled: Boolean)

/** Root hiding and su switches; null when the phone could not say (feature missing, camd not there). */
data class SavedFeatures(
    val suCompat: Boolean? = null,
    val kernelUmount: Boolean? = null,
    val selinuxHide: Boolean? = null,
    val hideBootloader: Boolean? = null,
    val defaultUmountModules: Boolean? = null,
)

/**
 * Cam's configuration in one file, for a new phone or after flashing a ROM: who gets root and
 * with what profile, the root hiding switches, which modules are on, and the Files tab's
 * bookmarks. Modules themselves are not in it (they can be large and carry their own data):
 * a restore turns on and off the ones that are installed and lists the missing ones.
 */
data class ConfigBackup(
    val created: Long,
    val manager: String,
    val device: String,
    val features: SavedFeatures,
    val profiles: List<SavedProfile>,
    val modules: List<SavedModule>,
    val bookmarks: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("created", created)
        .put("manager", manager)
        .put("device", device)
        .put("features", JSONObject().apply {
            features.suCompat?.let { put("suCompat", it) }
            features.kernelUmount?.let { put("kernelUmount", it) }
            features.selinuxHide?.let { put("selinuxHide", it) }
            features.hideBootloader?.let { put("hideBootloader", it) }
            features.defaultUmountModules?.let { put("defaultUmountModules", it) }
        })
        .put("profiles", JSONArray(profiles.map { p ->
            profileJson(p.profile).put("key", p.key).put("user", p.userId).put("rules", p.rules)
        }))
        .put("modules", JSONArray(modules.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("version", m.version).put("enabled", m.enabled)
        }))
        .put("files", JSONObject().put("bookmarks", JSONArray(bookmarks)))
        .toString(2)

    companion object {
        const val FORMAT = "cam-config"
        const val VERSION = 1
        const val MIME = "application/json"

        /** Reads a backup; IllegalArgumentException when [text] is not one, or is from a newer Cam. */
        fun parse(text: String): ConfigBackup {
            val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("not JSON", it) }
            require(root.optString("format") == FORMAT) { "not a Cam configuration backup" }
            require(root.optInt("version") in 1..VERSION) { "made by a newer Cam (version ${root.optInt("version")})" }
            val f = root.optJSONObject("features") ?: JSONObject()
            fun flag(name: String): Boolean? = if (f.has(name)) f.optBoolean(name) else null
            return ConfigBackup(
                created = root.optLong("created"),
                manager = root.optString("manager"),
                device = root.optString("device"),
                features = SavedFeatures(
                    suCompat = flag("suCompat"),
                    kernelUmount = flag("kernelUmount"),
                    selinuxHide = flag("selinuxHide"),
                    hideBootloader = flag("hideBootloader"),
                    defaultUmountModules = flag("defaultUmountModules"),
                ),
                profiles = root.optJSONArray("profiles").objects().mapNotNull { o ->
                    val key = o.optString("key").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    SavedProfile(key, o.optInt("user"), readProfile(o, key), o.optString("rules"))
                },
                modules = root.optJSONArray("modules").objects().mapNotNull { o ->
                    val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    SavedModule(id, o.optString("name"), o.optString("version"), o.optBoolean("enabled", true))
                },
                bookmarks = root.optJSONObject("files")?.optJSONArray("bookmarks").strings(),
            )
        }

        private fun profileJson(p: CamNative.Profile): JSONObject = JSONObject()
            .put("allowSu", p.allowSu)
            .put("rootUseDefault", p.rootUseDefault)
            .put("rootTemplate", p.rootTemplate ?: JSONObject.NULL)
            .put("uid", p.uid)
            .put("gid", p.gid)
            .put("groups", JSONArray(p.groups))
            .put("capabilities", JSONArray(p.capabilities))
            .put("context", p.context)
            .put("namespace", p.namespace)
            .put("nonRootUseDefault", p.nonRootUseDefault)
            .put("umountModules", p.umountModules)
            .put("flags", p.flags)

        private fun readProfile(o: JSONObject, key: String): CamNative.Profile {
            val d = CamNative.Profile(key)
            return d.copy(
                allowSu = o.optBoolean("allowSu", d.allowSu),
                rootUseDefault = o.optBoolean("rootUseDefault", d.rootUseDefault),
                rootTemplate = if (o.isNull("rootTemplate")) null else o.optString("rootTemplate"),
                uid = o.optInt("uid", d.uid),
                gid = o.optInt("gid", d.gid),
                groups = o.optJSONArray("groups").ints(),
                capabilities = o.optJSONArray("capabilities").ints(),
                context = o.optString("context", d.context),
                namespace = o.optInt("namespace", d.namespace),
                nonRootUseDefault = o.optBoolean("nonRootUseDefault", d.nonRootUseDefault),
                umountModules = o.optBoolean("umountModules", d.umountModules),
                flags = o.optLong("flags", d.flags),
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }

        private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
    }
}

/** What a restore changes on this phone, worked out before anything is written. */
data class RestorePlan(
    /** profiles to write, each with this phone's uid for its app */
    val profiles: List<SavedProfile>,
    /** apps in the backup that are not installed here: "label (user)" for the screen */
    val missingApps: List<String>,
    /** installed modules whose on/off differs from the backup */
    val modules: List<SavedModule>,
    /** modules in the backup that are not installed here */
    val missingModules: List<SavedModule>,
    /** bookmarks the Files tab does not have yet */
    val newBookmarks: List<String>,
) {
    companion object {
        /**
         * [installed]: (profile key, user) to this phone's uid. [modules]: installed module id to
         * whether it is on. [bookmarks]: the Files tab's now. A restore only adds and changes:
         * apps and bookmarks the backup does not name stay as they are.
         */
        fun of(
            backup: ConfigBackup,
            installed: Map<Pair<String, Int>, Int>,
            modules: Map<String, Boolean>,
            bookmarks: List<String>,
        ): RestorePlan {
            val (here, missing) = backup.profiles.partition { (it.key to it.userId) in installed }
            return RestorePlan(
                profiles = here.map { p ->
                    val uid = installed.getValue(p.key to p.userId)
                    p.copy(profile = p.profile.copy(name = p.key, currentUid = uid))
                },
                missingApps = missing.map { if (it.userId == 0) it.key else "${it.key} (${it.userId})" },
                modules = backup.modules.filter { it.id in modules && modules[it.id] != it.enabled },
                missingModules = backup.modules.filter { it.id !in modules },
                newBookmarks = backup.bookmarks.filter { it !in bookmarks }.distinct(),
            )
        }
    }
}
