package cam.su.kernel.data.repository

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cam.su.kernel.camApp
import cam.su.kernel.hiding.HidingRules
import okhttp3.CacheControl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Where the rules stand after a check, for the page to show. */
data class HidingRulesStatus(
    val rules: HidingRules,
    /** the last successful rules download (not the Duck Detector lookup); null when never */
    val checkedAt: Long?,
    /** the last check brought newer rules */
    val updated: Boolean = false,
    /** the last check could not get the rules */
    val failed: Boolean = false,
    /** the newest Duck Detector nightly commit and its time; null until known, or when the last check could not get it */
    val duckLatest: String? = null,
    val duckLatestAt: String? = null,
    /** the last check could not get a recent Duck Detector nightly */
    val duckFailed: Boolean = false,
) {
    /**
     * Duck Detector has a nightly newer than the commit these rules follow. Compared by commit
     * date: the pin can be a commit no nightly has yet, and an older nightly is not ahead of it.
     * Rules without [HidingRules.duckDetectorAt] (before version 13) fall back to "differs".
     */
    val duckAhead: Boolean
        get() {
            val latest = duckLatest ?: return false
            val pin = rules.duckDetector
            if (pin.isEmpty() || latest.startsWith(pin)) return false
            val pinAt = runCatching { Instant.parse(rules.duckDetectorAt) }.getOrNull() ?: return true
            // parseDuck refuses an answer without a readable date, so this is only ever an old saved one
            val latestAt = runCatching { Instant.parse(duckLatestAt) }.getOrNull() ?: return false
            return latestAt > pinAt
        }
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
        // Byte-for-byte copy of assets/$ASSET (and .sig) published by site/scripts/build-feed.mjs
        // on every change, so rule updates keep working once the repository is private.
        private const val RULES_URL = "https://caesarvn.xyz/feed/$ASSET"
        // Duck Detector's newest nightly, picked by scripts/duck_nightly.sh (the one place that knows
        // how Duck tags its nightlies) and published by deploy-site.yml every day.
        private const val DUCK_URL = "https://caesarvn.xyz/feed/duck-nightly.json"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /**
         * How old the feed's answer may be. deploy-site.yml asks GitHub daily and, when GitHub
         * does not answer, publishes the last answer again; past this it is not shown as current.
         */
        val DUCK_MAX_AGE: Duration = Duration.ofDays(3)

        /** How far `checked` may run ahead of the site's clock (GitHub's runner and the VPS differ a little). */
        private val CLOCK_SLACK: Duration = Duration.ofHours(1)

        /**
         * The commit and its date from feed/duck-nightly.json (`{"sha", "tag", "date", "checked"}`).
         * Refused when the date is unreadable (it could not be compared with the pin), or when
         * `checked` (when the site last asked GitHub) is more than [DUCK_MAX_AGE] before [now].
         * [now] is the site's clock (the response's Date header), so a wrong device clock does not count.
         */
        fun parseDuck(json: String, now: Instant): Pair<String, String> {
            val obj = JSONObject(json)
            val sha = obj.getString("sha")
            require(sha.matches(Regex("[0-9a-f]{40}"))) { "not a commit: $sha" }
            val date = obj.getString("date")
            Instant.parse(date)
            val checked = Instant.parse(obj.getString("checked"))
            val age = Duration.between(checked, now)
            require(age <= DUCK_MAX_AGE && age >= CLOCK_SLACK.negated()) { "Duck Detector nightly checked $checked, site time $now" }
            return sha to date
        }

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
    fun status(): HidingRulesStatus {
        // an answer saved without duckCheckedAt came from the old GitHub lookup (before 2026-10-21),
        // which kept it when a refresh failed: not shown, the next check asks again
        val duckKnown = prefs.getLong("duckCheckedAt", 0L) > 0
        return HidingRulesStatus(
            rules = current(),
            checkedAt = prefs.getLong("rulesCheckedAt", 0L).takeIf { it > 0 },
            duckLatest = prefs.getString("duckLatest", null)?.takeIf { duckKnown },
            duckLatestAt = prefs.getString("duckLatestAt", null)?.takeIf { duckKnown },
        )
    }

    /**
     * Asks the feed for whatever is due: the rules and the Duck Detector nightly each at most
     * every [CHECK_INTERVAL_MS] after their last success, unless [force]. A failed Duck lookup
     * is retried on the next visit without downloading the rules again. Blocking.
     */
    fun check(force: Boolean): HidingRulesStatus {
        val before = status()
        val now = System.currentTimeMillis()
        val rulesDue = force || isDue("rulesCheckedAt", now)
        val duckDue = force || isDue("duckCheckedAt", now)
        if (!rulesDue && !duckDue) return before

        var failed = false
        var duckFailed = false
        var updated = false
        if (rulesDue) runCatching { fetchRules(before.rules) }
            .onSuccess { updated = it; prefs.edit { putLong("rulesCheckedAt", now) } }
            .onFailure { failed = true; Log.w(TAG, "rules check failed", it) }
        // An answer that could not be refreshed is dropped, not shown: an old one can point at a
        // commit behind the rules' pin and claim Duck moved ahead.
        if (duckDue) runCatching { fetchDuck() }
            .onSuccess { (sha, at) ->
                prefs.edit { putString("duckLatest", sha); putString("duckLatestAt", at); putLong("duckCheckedAt", now) }
            }
            .onFailure {
                duckFailed = true
                prefs.edit { remove("duckLatest"); remove("duckLatestAt"); remove("duckCheckedAt") }
                Log.w(TAG, "Duck Detector check failed", it)
            }

        return status().copy(updated = updated, failed = failed, duckFailed = duckFailed)
    }

    /**
     * The rules only (no Duck Detector lookup), at most every [CHECK_INTERVAL_MS]: what a module
     * install checks against. Shares [check]'s timer, so the page does not download them again
     * right after an install did. Failures leave [current] as it is. Blocking.
     */
    fun refreshRules() {
        val now = System.currentTimeMillis()
        if (!isDue("rulesCheckedAt", now)) return
        runCatching { fetchRules(current()) }
            .onSuccess { prefs.edit { putLong("rulesCheckedAt", now) } }
            .onFailure { Log.w(TAG, "rules refresh failed", it) }
    }

    /**
     * No success saved under [key] within [CHECK_INTERVAL_MS] of [now]. A time in the future (the
     * clock was set back) counts as due, or nothing would be asked until the clock caught up.
     */
    private fun isDue(key: String, now: Long): Boolean =
        prefs.getLong(key, 0L).let { it <= 0 || now - it !in 0 until CHECK_INTERVAL_MS }

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
        // past the cache every time (a no-cache request skips OkHttp's conditional GET: a full,
        // small download), so the Date header is the site's clock now and not a cached copy's
        val (body, siteTime) = download(DUCK_URL, skipCache = true)
        return parseDuck(String(body, Charsets.UTF_8), siteTime ?: Instant.now())
    }

    /** The body as sent: the signature covers these exact bytes. */
    private fun get(url: String): ByteArray = download(url).first

    /** The body as sent, and the server's Date header. */
    private fun download(url: String, skipCache: Boolean = false): Pair<ByteArray, Instant?> {
        // otherwise the client's cache revalidates with ETag, so an unchanged file costs a 304
        val request = Request.Builder().url(url)
            .apply { if (skipCache) cacheControl(CacheControl.Builder().noCache().build()) }
            .build()
        return camApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            response.body.bytes() to response.headers.getDate("Date")?.toInstant()
        }
    }
}
