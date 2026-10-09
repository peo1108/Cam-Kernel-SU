package cam.su.kernel.data.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cam.su.kernel.camApp
import cam.su.kernel.hiding.HidingRules
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Where the rules stand after a check, for the page to show. */
data class HidingRulesStatus(
    val rules: HidingRules,
    /** when the repo was last asked; null when never */
    val checkedAt: Long?,
    /** the last check brought newer rules */
    val updated: Boolean = false,
    /** the last check could not reach the repo or GitHub */
    val failed: Boolean = false,
    /** the newest Duck Detector nightly commit and its time; null until known */
    val duckLatest: String? = null,
    val duckLatestAt: String? = null,
) {
    /** Duck Detector moved on since these rules were written. */
    val duckAhead: Boolean
        get() = duckLatest != null && rules.duckDetector.isNotEmpty() && !duckLatest.startsWith(rules.duckDetector)
}

/**
 * The rules [cam.su.kernel.hiding.AppViewProbe] uses: the copy in the APK, or a newer one
 * signed with the key of scripts/sign_hiding_rules.py and downloaded from this repo.
 * Only data comes down, never code, and nothing is fetched in the background: the hiding
 * check page asks, at most every [CHECK_INTERVAL_MS] unless the user asks again.
 */
class HidingRulesRepository(private val context: Context = camApp) {

    companion object {
        private const val TAG = "HidingRules"
        private const val ASSET = "hiding-rules.json"
        private const val RULES_URL = "https://raw.githubusercontent.com/peo1108/Cam-Kernel-SU/main/manager/app/src/main/assets/$ASSET"
        private const val DUCK_URL = "https://api.github.com/repos/eltavine/Duck-Detector-Refactoring/commits/nightly"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** ECDSA P-256 public key (SubjectPublicKeyInfo, base64) the rules are signed with. */
        private const val PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEu3Yx00JriPyJqGlzaxHIyZniPawfvFiRl8EZM/CZseAOINjTd4Ul6l3QKm1yQxiFrel3Erk3cKn8zz2PUW+NiA=="

        /** [data] signed by the rules key; [signature] is the base64 DER signature. */
        fun verify(data: ByteArray, signature: String): Boolean = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(Base64.getDecoder().decode(signature.trim()))
            }
        }.getOrDefault(false)
    }

    private val downloaded: File
        get() = File(context.filesDir, "hiding_check/rules.json")

    private val prefs
        get() = context.getSharedPreferences("hiding_check", Context.MODE_PRIVATE)

    private val bundled: HidingRules by lazy {
        HidingRules.parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            ?: error("bad bundled $ASSET")
    }

    /** The newest rules at hand: a verified download newer than the APK's copy, else that copy. */
    fun current(): HidingRules {
        val saved = runCatching { HidingRules.parse(downloaded.readText()) }.getOrNull()
        return if (saved != null && saved.version > bundled.version) saved else bundled
    }

    /** [current] written out for camd, which reads the rules from a file (`hiding-audit --rules`). */
    fun currentFile(): File = File(context.filesDir, "hiding_check/rules-current.json").apply {
        parentFile?.mkdirs()
        writeText(current().toJson())
    }

    /** What the page shows before (or without) a check. */
    fun status(): HidingRulesStatus = HidingRulesStatus(
        rules = current(),
        checkedAt = prefs.getLong("rulesCheckedAt", 0L).takeIf { it > 0 },
        duckLatest = prefs.getString("duckLatest", null),
        duckLatestAt = prefs.getString("duckLatestAt", null),
    )

    /** Asks the repo and GitHub, unless that happened less than [CHECK_INTERVAL_MS] ago and [force] is off. Blocking. */
    fun check(force: Boolean): HidingRulesStatus {
        val before = status()
        val now = System.currentTimeMillis()
        if (!force && before.checkedAt != null && now - before.checkedAt < CHECK_INTERVAL_MS) return before

        var failed = false
        var updated = false
        runCatching { fetchRules(before.rules) }
            .onSuccess { updated = it }
            .onFailure { failed = true; Log.w(TAG, "rules check failed", it) }
        runCatching { fetchDuck() }
            .onSuccess { (sha, at) -> prefs.edit { putString("duckLatest", sha); putString("duckLatestAt", at) } }
            .onFailure { failed = true; Log.w(TAG, "Duck Detector check failed", it) }
        if (!failed) prefs.edit { putLong("rulesCheckedAt", now) }

        return status().copy(updated = updated, failed = failed)
    }

    /** Downloads the rules and their signature; true when a newer, verified copy was saved. */
    private fun fetchRules(have: HidingRules): Boolean {
        val data = get(RULES_URL)
        val rules = HidingRules.parse(String(data, Charsets.UTF_8)) ?: error("unreadable rules")
        if (rules.version <= have.version) return false
        if (!verify(data, String(get("$RULES_URL.sig"), Charsets.US_ASCII))) error("rules signature does not match")
        downloaded.parentFile?.mkdirs()
        downloaded.writeBytes(data)
        return true
    }

    /** The Duck Detector nightly commit and when it was made. */
    private fun fetchDuck(): Pair<String, String> {
        val obj = JSONObject(String(get(DUCK_URL), Charsets.UTF_8))
        return obj.getString("sha") to obj.getJSONObject("commit").getJSONObject("committer").getString("date")
    }

    /** The body as sent: the signature covers these exact bytes. */
    private fun get(url: String): ByteArray {
        // the client's cache revalidates with ETag, so an unchanged file costs a 304
        val request = Request.Builder().url(url).build()
        return camApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            response.body.bytes()
        }
    }
}
