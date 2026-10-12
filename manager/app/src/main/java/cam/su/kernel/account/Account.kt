package cam.su.kernel.account

import android.content.Context
import android.os.Build
import cam.su.kernel.camApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * The caesarvn.xyz account this phone is linked to (site/api/routes/device.mjs).
 *
 * Linking never asks for a password in this root app: the app gets a pairing code, the owner
 * confirms it on the website while signed in, and the app receives a device token. Updates and
 * downloads send that token; the server decides what this account may install (membership, the
 * 20-day wait, early access, beta tester).
 */
object Account {
    const val SITE = "https://caesarvn.xyz"
    private const val PREFS = "cam_account"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_EMAIL = "email"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun prefs(context: Context = camApp) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The device token, or null when this phone is not linked. */
    fun token(): String? = prefs().getString(KEY_TOKEN, null)

    fun email(): String? = prefs().getString(KEY_EMAIL, null)

    fun isLinked(): Boolean = token() != null

    private fun save(token: String, email: String?) {
        prefs().edit().putString(KEY_TOKEN, token).putString(KEY_EMAIL, email).apply()
    }

    /** Forgets the link locally (the server already dropped it, or the owner unlinked). */
    fun forget() {
        prefs().edit().remove(KEY_TOKEN).remove(KEY_EMAIL).apply()
    }

    /** Adds the device token to requests for this site only. */
    fun authorize(builder: Request.Builder, url: String): Request.Builder {
        val token = token()
        if (token != null && url.startsWith("$SITE/")) builder.header("Authorization", "Bearer $token")
        return builder
    }

    fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private fun call(method: String, path: String, body: String? = null, auth: Boolean = false): Pair<Int, String> {
        val builder = Request.Builder().url("$SITE$path")
        if (method == "POST") builder.post((body ?: "{}").toRequestBody(JSON)) else builder.get()
        if (auth) authorize(builder, "$SITE$path")
        camApp.okhttpClient.newCall(builder.build()).execute().use { response ->
            return response.code to response.body.string()
        }
    }

    /** Step 1: ask for a pairing code to show (and a URL for the QR). */
    suspend fun startPairing(): Result<Pairing> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = call("POST", "/api/devices/start", JSONObject().put("name", deviceName()).toString())
            if (code != 200) throw IOException("HTTP $code")
            parsePairing(text)
        }
    }

    /** Step 2: poll until the owner confirmed the code on the website. Saves the token when linked. */
    suspend fun poll(id: String): Result<PollResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = call("GET", "/api/devices/poll?id=$id")
            if (code != 200) throw IOException("HTTP $code")
            parsePoll(text).also { if (it is PollResult.Linked) save(it.token, it.email) }
        }
    }

    /** Membership, beta tester and waiting releases of the linked account. Null when not linked. */
    suspend fun status(): Result<AccountStatus?> = withContext(Dispatchers.IO) {
        if (!isLinked()) return@withContext Result.success(null)
        runCatching {
            val (code, text) = call("GET", "/api/ota/status", auth = true)
            when (code) {
                200 -> parseStatus(text).also { save(token()!!, it.email) }
                401 -> {
                    // Unlinked from the website, or the account was locked.
                    forget()
                    null
                }
                else -> throw IOException("HTTP $code")
            }
        }
    }

    /** The beta switch was turned on: ask to be a beta tester (Cam approves on the website). */
    suspend fun requestTester(): Result<String> = withContext(Dispatchers.IO) {
        if (!isLinked()) return@withContext Result.failure(IllegalStateException("not linked"))
        runCatching {
            val (code, text) = call("POST", "/api/tester/request", auth = true)
            when (code) {
                200 -> JSONObject(text).optString("status", "pending")
                403 -> "members_only"
                else -> throw IOException("HTTP $code")
            }
        }
    }

    suspend fun unlink(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isLinked()) runCatching { call("POST", "/api/devices/self/unlink", auth = true) }
            forget()
        }
    }
}

data class Pairing(val id: String, val code: String, val url: String, val expiresInSeconds: Int)

sealed interface PollResult {
    data object Pending : PollResult
    data object Expired : PollResult
    data class Linked(val token: String, val email: String?) : PollResult
}

/** [tester]: "none", "pending", "approved" or "rejected". */
data class AccountStatus(
    val email: String,
    val member: Boolean,
    val tester: String,
    val locked: List<LockedRelease>,
    val joinUrl: String,
)

data class LockedRelease(val version: String, val openAt: Long, val prerelease: Boolean, val price: Long)

internal fun parsePairing(json: String): Pairing {
    val o = JSONObject(json)
    return Pairing(o.getString("id"), o.getString("code"), o.getString("url"), o.optInt("expires_in", 600))
}

internal fun parsePoll(json: String): PollResult {
    val o = JSONObject(json)
    return when (o.optString("status")) {
        "linked" -> PollResult.Linked(o.getString("token"), o.optString("email").takeIf { it.isNotEmpty() && it != "null" })
        "pending" -> PollResult.Pending
        else -> PollResult.Expired
    }
}

internal fun parseStatus(json: String): AccountStatus {
    val o = JSONObject(json)
    val locked = o.optJSONArray("locked")
    return AccountStatus(
        email = o.getString("email"),
        member = o.optBoolean("member"),
        tester = o.optString("tester", "none"),
        locked = (0 until (locked?.length() ?: 0)).map { i ->
            val r = locked!!.getJSONObject(i)
            LockedRelease(r.getString("version"), r.optLong("open_at"), r.optBoolean("prerelease"), r.optLong("price"))
        },
        joinUrl = o.optString("join_url", "${Account.SITE}/thanh-vien"),
    )
}
