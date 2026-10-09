package cam.su.kernel.hiding

import org.json.JSONArray
import org.json.JSONObject

/**
 * What [AppViewProbe] looks for. The app ships assets/hiding-rules.json and may replace it
 * with a newer signed copy from the repo (HidingRulesRepository).
 */
data class HidingRules(
    val version: Int,
    /** yyyy-MM-dd */
    val updated: String,
    /** the Duck Detector commit these rules follow */
    val duckDetector: String,
    /** mount sources root tooling uses; a stock device never shows them to apps */
    val mountSources: Set<String>,
    /** mapped paths naming root or hook tooling, lower case */
    val mapMarkers: List<String>,
    val suPaths: List<String>,
    /** property to the value a locked, stock device reports */
    val safeProps: Map<String, String>,
) {
    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("updated", updated)
        .put("duckDetector", duckDetector)
        .put("mountSources", JSONArray(mountSources.toList()))
        .put("mapMarkers", JSONArray(mapMarkers))
        .put("suPaths", JSONArray(suPaths))
        .put("safeProps", JSONObject(safeProps))
        .toString()

    companion object {
        fun parse(json: String): HidingRules? = runCatching {
            val obj = JSONObject(json)
            val props = obj.getJSONObject("safeProps")
            HidingRules(
                version = obj.getInt("version"),
                updated = obj.optString("updated"),
                duckDetector = obj.optString("duckDetector"),
                mountSources = strings(obj.getJSONArray("mountSources")).toSet(),
                mapMarkers = strings(obj.getJSONArray("mapMarkers")).map(String::lowercase),
                suPaths = strings(obj.getJSONArray("suPaths")),
                safeProps = props.keys().asSequence().associateWith { props.getString(it) },
            )
        }.getOrNull()

        private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }
    }
}
