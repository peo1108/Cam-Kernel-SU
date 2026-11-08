package cam.caesar.vn.attest

import org.json.JSONArray
import org.json.JSONObject

/** A target profile the user configured: a keybox and the apps it signs for. */
data class AttestProfile(
    val id: String,
    val keyboxB64: String,
    val securityLevel: Int,
    val packages: List<String>,
)

/**
 * Build the control-protocol `config` push (spec §3.2) from the profiles, the frozen harvest, and a
 * uid resolver. `uidsOf(pkg)` returns the (uid, userId) pairs a package resolves to — a work-profile
 * clone of an app is a second pair (same package, a different uid and user), and each pair becomes a
 * distinct target entry so one profile's key is never served to the other's caller.
 *
 * The profile's OS/patch levels come from the harvest (the device's real values); a future override
 * UI (M3-6) uses [osVersionInt]/[osPatchLevelInt]/[datePatchLevelInt] to supply its own.
 */
fun resolvePush(
    profiles: List<AttestProfile>,
    harvest: HarvestRecord,
    uidsOf: (String) -> List<Pair<Int, Int>>,
): String {
    val root = JSONObject()
    root.put("type", "config")
    root.put("epoch", 0)
    root.put("bootInfo", JSONObject(harvest.toBootInfoJson()))
    val profilesArr = JSONArray()
    for (p in profiles) {
        val po = JSONObject()
        po.put("id", p.id)
        po.put("securityLevel", p.securityLevel)
        if (p.keyboxB64.isNotEmpty()) po.put("keyboxB64", p.keyboxB64)
        harvest.osVersion?.let { po.put("osVersion", it) }
        harvest.osPatchLevel?.let { po.put("osPatchLevel", it) }
        harvest.vendorPatchLevel?.let { po.put("vendorPatchLevel", it) }
        harvest.bootPatchLevel?.let { po.put("bootPatchLevel", it) }
        val packages = JSONArray()
        val packageUsers = JSONArray()
        val uids = JSONArray()
        val uidPackages = JSONArray()
        for (pkg in p.packages) {
            for ((uid, user) in uidsOf(pkg)) {
                packages.put(pkg)
                packageUsers.put(user)
                uids.put(uid)
                uidPackages.put(pkg)
            }
        }
        po.put("packages", packages)
        po.put("packageUsers", packageUsers)
        po.put("uids", uids)
        po.put("uidPackages", uidPackages)
        profilesArr.put(po)
    }
    root.put("profiles", profilesArr)
    return root.toString()
}
