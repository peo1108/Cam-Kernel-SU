package cam.su.kernel.data.model

import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import cam.su.kernel.CamNative
import cam.su.kernel.profile.Capabilities
import cam.su.kernel.profile.Groups
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Immutable
@Parcelize
data class TemplateInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val author: String = "",
    val local: Boolean = true,
    val namespace: Int = CamNative.Profile.Namespace.INHERITED.ordinal,
    val uid: Int = CamNative.ROOT_UID,
    val gid: Int = CamNative.ROOT_GID,
    val groups: List<Int> = mutableListOf(),
    val capabilities: List<Int> = mutableListOf(),
    val context: String = CamNative.CAM_DOMAIN,
    val rules: List<String> = mutableListOf(),
    val flags: List<Int> = mutableListOf(
        CamNative.Profile.RootProfileFlag.NO_NEW_PRIVS.ordinal // default no new privs for new template
    )
) : Parcelable {
    companion object {
        private const val TAG = "TemplateInfo"

        fun fromJSON(templateJson: JSONObject): TemplateInfo? {
            return runCatching {
                val groupsJsonArray = templateJson.optJSONArray("groups")
                val capabilitiesJsonArray = templateJson.optJSONArray("capabilities")
                val flagsJsonArray = templateJson.optJSONArray("flags")
                val context = templateJson.optString("context").takeIf { it.isNotEmpty() }
                    ?: CamNative.CAM_DOMAIN
                val namespace = templateJson.optString("namespace").takeIf { it.isNotEmpty() }
                    ?: CamNative.Profile.Namespace.INHERITED.name

                val rulesJsonArray = templateJson.optJSONArray("rules")
                val templateInfo = TemplateInfo(
                    id = templateJson.getString("id"),
                    name = getLocaleString(templateJson, "name"),
                    description = getLocaleString(templateJson, "description"),
                    author = templateJson.optString("author"),
                    local = templateJson.optBoolean("local"),
                    namespace = CamNative.Profile.Namespace.valueOf(
                        namespace.uppercase()
                    ).ordinal,
                    uid = templateJson.optInt("uid", CamNative.ROOT_UID),
                    gid = templateJson.optInt("gid", CamNative.ROOT_GID),
                    groups = getEnumOrdinals(groupsJsonArray, Groups::class.java).map { it.gid },
                    capabilities = getEnumOrdinals(
                        capabilitiesJsonArray, Capabilities::class.java
                    ).map { it.cap },
                    context = context,
                    rules = rulesJsonArray?.mapCatching<String, String>({ it }, {
                        Log.e(TAG, "ignore invalid rule: $it", it)
                    }).orEmpty(),
                    flags = flagsJsonArray?.let {
                        getEnumOrdinals(
                            it,
                            CamNative.Profile.RootProfileFlag::class.java
                        ).map { flag -> flag.ordinal }
                    } ?: listOf(CamNative.Profile.RootProfileFlag.NO_NEW_PRIVS.ordinal)
                )
                templateInfo
            }.onFailure {
                Log.e(TAG, "ignore invalid template: $it", it)
            }.getOrNull()
        }

        private fun getLocaleString(json: JSONObject, key: String): String {
            val fallback = json.getString(key)
            val locale = Locale.getDefault()
            val localeKey = "${locale.language}_${locale.country}"
            json.optJSONObject("locales")?.let {
                // check locale first
                it.optJSONObject(localeKey)?.let { json ->
                    return json.optString(key, fallback)
                }
                // fallback to language
                it.optJSONObject(locale.language)?.let { json ->
                    return json.optString(key, fallback)
                }
            }
            return fallback
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T, R> JSONArray.mapCatching(
            transform: (T) -> R, onFail: (Throwable) -> Unit
        ): List<R> {
            return List(length()) { i -> get(i) as T }.mapNotNull { element ->
                runCatching {
                    transform(element)
                }.onFailure(onFail).getOrNull()
            }
        }

        private inline fun <reified T : Enum<T>> getEnumOrdinals(
            jsonArray: JSONArray?, enumClass: Class<T>
        ): List<T> {
            return jsonArray?.mapCatching<String, T>({ name ->
                enumValueOf(name.uppercase())
            }, {
                Log.e(TAG, "ignore invalid enum ${enumClass.simpleName}: $it", it)
            }).orEmpty()
        }
    }

    fun toJSON(): JSONObject {
        val template = this
        return JSONObject().apply {

            put("id", template.id)
            put("name", template.name.ifBlank { template.id })
            put("description", template.description.ifBlank { template.id })
            if (template.author.isNotEmpty()) {
                put("author", template.author)
            }
            put("namespace", CamNative.Profile.Namespace.entries[template.namespace].name)
            put("uid", template.uid)
            put("gid", template.gid)

            if (template.groups.isNotEmpty()) {
                put(
                    "groups", JSONArray(
                        Groups.entries.filter {
                            template.groups.contains(it.gid)
                        }.map {
                            it.name
                        }
                    ))
            }

            if (template.capabilities.isNotEmpty()) {
                put(
                    "capabilities", JSONArray(
                        Capabilities.entries.filter {
                            template.capabilities.contains(it.cap)
                        }.map {
                            it.name
                        }
                    ))
            }

            if (template.context.isNotEmpty()) {
                put("context", template.context)
            }

            if (template.rules.isNotEmpty()) {
                put("rules", JSONArray(template.rules))
            }

            put(
                "flags", JSONArray(
                    CamNative.Profile.RootProfileFlag.entries.filter {
                        template.flags.contains(it.ordinal)
                    }.map {
                        it.name
                    }
                )
            )
        }
    }
}
