package cam.su.kernel.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import cam.su.kernel.R
import java.io.File

/**
 * Changes the Manager's own launcher name and icon directly, with root.
 *
 * Android gives an app no API to repaint its own drawer entry, so this writes a Runtime Resource
 * Overlay (`cmd overlay fabricate`) over `string/app_name` and `mipmap/ic_launcher`. The overlay is
 * owned by the shell (uid 0), so it needs root but survives reboots and app updates.
 */
object AppIdentity {

    /** Preset icons bundled in the APK; a custom one comes from the gallery instead. */
    enum class Preset(@StringRes val label: Int, @DrawableRes val icon: Int) {
        MoMo(R.string.app_identity_momo, R.mipmap.ic_identity_momo),
        TikTok(R.string.app_identity_tiktok, R.mipmap.ic_identity_tiktok),
    }

    sealed interface Icon {
        data object Default : Icon
        data class OfPreset(val preset: Preset) : Icon
        data object Custom : Icon
    }

    private const val PREFS = "app_identity"
    private const val KEY_NAME = "name"
    private const val KEY_ICON = "icon"

    private const val OVERLAY_OWNER = "com.android.shell"
    private const val NAME_OVERLAY = "KsuIdentityName"
    private const val ICON_OVERLAY = "KsuIdentityIcon"

    /** idmap can only read the overlay source from the resource cache partition. */
    private const val STAGE = "/data/resource-cache/ksu_identity_icon.png"

    /** 108dp adaptive icon at xxxhdpi. */
    private const val ICON_SIZE = 432
    private const val CUSTOM = "custom"

    private fun pkg(context: Context) = context.packageName

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun savedIcon(context: Context): Icon = decodeIcon(prefs(context).getString(KEY_ICON, null))

    fun customIconFile(context: Context) = File(context.filesDir, "app_identity_icon.png")

    fun loadCustomIcon(context: Context): Bitmap? =
        customIconFile(context).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** The icon as a square bitmap for preview and for staging into the overlay. */
    fun iconBitmap(context: Context, icon: Icon, size: Int = ICON_SIZE): Bitmap? = when (icon) {
        Icon.Default -> ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(size, size)
        is Icon.OfPreset -> ContextCompat.getDrawable(context, icon.preset.icon)?.toBitmap(size, size)
        Icon.Custom -> loadCustomIcon(context)?.let { Bitmap.createScaledBitmap(it, size, size, true) }
    }

    /** Center-crops the picked image to a square and keeps it in app storage. */
    fun importIcon(context: Context, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        if (shortSide <= 0) return false
        var sample = 1
        while (shortSide / (sample * 2) >= ICON_SIZE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val side = minOf(decoded.width, decoded.height)
        val left = (decoded.width - side) / 2
        val top = (decoded.height - side) / 2
        val icon = createBitmap(ICON_SIZE, ICON_SIZE)
        Canvas(icon).drawBitmap(decoded, Rect(left, top, left + side, top + side), Rect(0, 0, ICON_SIZE, ICON_SIZE), null)
        decoded.recycle()
        customIconFile(context).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    /**
     * Applies [name] and [icon] to the launcher entry. A blank name or [Icon.Default] clears that
     * part back to the APK's own value. Runs root shell commands; returns true on success.
     */
    fun apply(context: Context, name: String, icon: Icon): Boolean {
        val pkg = pkg(context)
        val commands = mutableListOf<String>()

        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            commands += "cmd overlay disable --user 0 $OVERLAY_OWNER:$NAME_OVERLAY"
        } else {
            commands += "cmd overlay fabricate --target $pkg --name $NAME_OVERLAY" +
                " $pkg:string/app_name string ${shellQuote(trimmed)}"
            commands += "cmd overlay enable --user 0 $OVERLAY_OWNER:$NAME_OVERLAY"
        }

        if (icon == Icon.Default) {
            commands += "cmd overlay disable --user 0 $OVERLAY_OWNER:$ICON_OVERLAY"
        } else {
            val bitmap = iconBitmap(context, icon) ?: return false
            val src = File(context.cacheDir, "identity_stage.png")
            src.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            commands += "cp ${shellQuote(src.absolutePath)} $STAGE"
            commands += "chcon u:object_r:resourcecache_data_file:s0 $STAGE"
            commands += "chmod 644 $STAGE"
            commands += "cmd overlay fabricate --target $pkg --name $ICON_OVERLAY" +
                " $pkg:mipmap/ic_launcher drawable $STAGE"
            commands += "cmd overlay enable --user 0 $OVERLAY_OWNER:$ICON_OVERLAY"
            commands += "rm -f $STAGE"
        }

        val result = getRootShell().newJob().add(*commands.toTypedArray()).exec()
        if (result.isSuccess) {
            prefs(context).edit {
                if (trimmed.isEmpty()) remove(KEY_NAME) else putString(KEY_NAME, trimmed)
                putString(KEY_ICON, encodeIcon(icon))
            }
        }
        return result.isSuccess
    }

    private fun encodeIcon(icon: Icon) = when (icon) {
        Icon.Default -> ""
        is Icon.OfPreset -> icon.preset.name
        Icon.Custom -> CUSTOM
    }

    private fun decodeIcon(value: String?): Icon = when (value) {
        null, "" -> Icon.Default
        CUSTOM -> Icon.Custom
        else -> Preset.entries.firstOrNull { it.name == value }?.let { Icon.OfPreset(it) } ?: Icon.Default
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
