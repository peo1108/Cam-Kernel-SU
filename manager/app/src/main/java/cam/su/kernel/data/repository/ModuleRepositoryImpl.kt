package cam.su.kernel.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cam.su.kernel.data.model.Module
import cam.su.kernel.data.model.ModuleUpdateInfo
import cam.su.kernel.data.model.forModule
import cam.su.kernel.data.model.visible
import cam.su.kernel.camApp
import cam.su.kernel.ui.util.getBootGuardStatus
import cam.su.kernel.ui.util.isNetworkAvailable
import cam.su.kernel.ui.util.listModuleConflicts
import cam.su.kernel.ui.util.listModules
import cam.su.kernel.ui.util.module.sanitizeVersionString
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ModuleRepositoryImpl : ModuleRepository {

    companion object {
        private const val TAG = "ModuleRepository"
    }

    override suspend fun getModules(): Result<List<Module>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = listModules()
            val array = JSONArray(result)
            // one camd call each for the whole list, not one per module
            val autoDisabled = getBootGuardStatus().autoDisabled.toSet()
            val settings = SettingsRepositoryImpl()
            val conflicts = if (settings.conflictDetection) {
                listModuleConflicts().visible(detection = true, includeProps = settings.conflictIncludeProps)
            } else {
                emptyList()
            }
            (0 until array.length())
                .asSequence()
                .map { array.getJSONObject(it) }
                .map { obj ->
                    val id = obj.getString("id")
                    Module(
                        id = id,
                        name = obj.optString("name"),
                        author = obj.optString("author", "Unknown"),
                        version = obj.optString("version", "Unknown"),
                        versionCode = obj.optInt("versionCode", 0),
                        description = obj.optString("description"),
                        enabled = obj.getBoolean("enabled"),
                        update = obj.optBoolean("update"),
                        remove = obj.getBoolean("remove"),
                        updateJson = obj.optString("updateJson"),
                        hasWebUi = obj.optBoolean("web"),
                        hasActionScript = obj.optBoolean("action"),
                        metamodule = (obj.optInt("metamodule") != 0) || obj.optBoolean("metamodule"),
                        actionIconPath = obj.optString("actionIcon").takeIf { it.isNotBlank() },
                        webUiIconPath = obj.optString("webuiIcon").takeIf { it.isNotBlank() },
                        autoDisabled = id in autoDisabled,
                        conflicts = conflicts.forModule(id),
                        backupVersion = if (obj.has("backupVersion")) obj.optString("backupVersion") else null,
                    )
                }.toList()
        }
    }

    override suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isNetworkAvailable(camApp)) {
                return@runCatching ModuleUpdateInfo.Empty
            }
            if (module.updateJson.isEmpty() || module.remove || module.update || !module.enabled) {
                return@runCatching ModuleUpdateInfo.Empty
            }

            val url = module.updateJson
            val response = camApp.okhttpClient.newCall(
                Request.Builder().url(url).build()
            ).execute()

            val result = if (response.isSuccessful) {
                response.body.string()
            } else {
                ""
            }

            if (result.isEmpty()) {
                return@runCatching ModuleUpdateInfo.Empty
            }

            val updateJson = JSONObject(result)
            var version = updateJson.optString("version", "")
            version = sanitizeVersionString(version)
            val versionCode = updateJson.optInt("versionCode", 0)
            val zipUrl = updateJson.optString("zipUrl", "")
            val changelog = updateJson.optString("changelog", "")

            if (versionCode <= module.versionCode || zipUrl.isEmpty()) {
                ModuleUpdateInfo.Empty
            } else {
                ModuleUpdateInfo(zipUrl, version, changelog)
            }
        }
    }
}
