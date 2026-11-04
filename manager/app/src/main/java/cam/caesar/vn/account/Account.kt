package cam.su.kernel.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    /**
     * Early access to [tag] (20,000đ by default), bought in the app (site/api/routes/pay.mjs). The
     * server hands back the waiting order for the same release instead of making a second one.
     */
    suspend fun buyEarly(tag: String): Result<Order> = withContext(Dispatchers.IO) {
        if (!isLinked()) return@withContext Result.failure(OrderException("sign_in"))
        runCatching {
            val (code, text) = call("POST", "/api/orders", JSONObject().put("kind", "early").put("tag", tag).toString(), auth = true)
            if (code != 200) throw OrderException(errorCode(text) ?: "http_$code")
            parseOrder(text)
        }
    }

    /** The order as the server sees it now: pending, paid or expired. */
    suspend fun order(orderCode: String): Result<Order> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = call("GET", "/api/orders/$orderCode", auth = true)
            if (code != 200) throw OrderException(errorCode(text) ?: "http_$code")
            parseOrder(text)
        }
    }

    /** The VietQR image of an order (drawn by SePay with the amount and transfer note filled in). */
    suspend fun qrImage(url: String): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            camApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                BitmapFactory.decodeStream(response.body.byteStream()) ?: throw IOException("not an image")
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

/**
 * [tester]: "none", "pending", "approved" or "rejected". [canBuyEarly]: early access is on sale
 * (member, selling turned on in /quan-tri, payments set up).
 */
data class AccountStatus(
    val email: String,
    val member: Boolean,
    val tester: String,
    val locked: List<LockedRelease>,
    val joinUrl: String,
    val canBuyEarly: Boolean = false,
)

/**
 * A release this account cannot install yet. [versionCode] comes from the APK's name (0 when the
 * server gave none); [changelog] is its CHANGELOG.md entry.
 */
data class LockedRelease(
    val version: String,
    val tag: String,
    val openAt: Long,
    val prerelease: Boolean,
    val price: Long,
    val versionCode: Long = 0,
    val changelog: String = "",
) {
    /** First "- " line of the notes, shown in the notification (like UpdateInfo.summary). */
    val summary: String?
        get() = changelog.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("- ") }?.removePrefix("- ")?.trim()
}

private val APK_VERSION_CODE = Regex("""_(\d+)-release\.apk$""")

/** Where to send the money. [content] is the transfer note: the order code the bank matches. */
data class PayInfo(val image: String, val bank: String, val account: String, val holder: String, val content: String)

/** [status]: "pending", "paid" or "expired". [received] is what came in so far (a short transfer). */
data class Order(
    val code: String,
    val tag: String?,
    val version: String?,
    val amount: Long,
    val received: Long,
    val status: String,
    val expiresAt: Long,
    val pay: PayInfo?,
) {
    val paid: Boolean get() = status == "paid"
    val due: Long get() = (amount - received).coerceAtLeast(0)
}

/** The server's error code ("sales_closed", "already_open", "members_only", ...). */
class OrderException(val code: String) : IOException(code)

/**
 * The newest locked release worth announcing: newer than the running app ([currentVersionCode])
 * and than the update it may already install ([openVersionCode], 0 when none). Betas only with
 * [includePrereleases] (they reach approved testers only, and those get every release at once).
 */
fun newestOffer(status: AccountStatus, currentVersionCode: Long, openVersionCode: Long = 0, includePrereleases: Boolean = false): LockedRelease? =
    status.locked
        .filter { it.versionCode > maxOf(currentVersionCode, openVersionCode) && (includePrereleases || !it.prerelease) }
        .maxByOrNull { it.versionCode }

internal fun errorCode(json: String): String? = runCatching { JSONObject(json).optString("error").ifEmpty { null } }.getOrNull()

internal fun parseOrder(json: String): Order {
    val o = JSONObject(json)
    val pay = o.optJSONObject("pay")?.let {
        PayInfo(it.getString("image"), it.optString("bank"), it.optString("account"), it.optString("holder"), it.getString("content"))
    }
    return Order(
        code = o.getString("code"),
        tag = o.optString("tag").takeIf { it.isNotEmpty() && it != "null" },
        version = o.optString("version").takeIf { it.isNotEmpty() && it != "null" },
        amount = o.getLong("amount"),
        received = o.optLong("received"),
        status = o.getString("status"),
        expiresAt = o.optLong("expires_at"),
        pay = pay,
    )
}

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
            LockedRelease(
                version = r.getString("version"),
                tag = r.optString("tag", "cam-v" + r.getString("version")),
                openAt = r.optLong("open_at"),
                prerelease = r.optBoolean("prerelease"),
                price = r.optLong("price"),
                versionCode = APK_VERSION_CODE.find(r.optString("apk"))?.groupValues?.get(1)?.toLongOrNull() ?: 0,
                changelog = if (r.isNull("body")) "" else r.optString("body"),
            )
        },
        joinUrl = o.optString("join_url", "${Account.SITE}/thanh-vien"),
        canBuyEarly = o.optBoolean("can_buy_early"),
    )
}
