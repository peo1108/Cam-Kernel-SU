package cam.su.kernel.ui.screen.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.account.Account
import cam.su.kernel.account.LockedRelease
import cam.su.kernel.account.Order
import cam.su.kernel.account.OrderException
import cam.su.kernel.ui.component.glass.GlassDialog
import cam.su.kernel.ui.component.markdown.MarkdownContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** What the popup shows for one release. */
private sealed interface EarlyUi {
    data object Loading : EarlyUi
    data class Message(val text: String, val button: String? = null, val url: String? = null) : EarlyUi
    /** Waiting days still running: open date, price, notes, the buy button. */
    data class Offer(val release: LockedRelease, val canBuy: Boolean) : EarlyUi
    /** An order waits for the transfer; [qr] once SePay's image arrived. */
    data class Paying(val order: Order, val qr: Bitmap? = null) : EarlyUi
    /** Paid here or on the website, or the release opened meanwhile: the update can go ahead. */
    data object Open : EarlyUi
}

private fun vnd(amount: Long): String = NumberFormat.getInstance(Locale("vi", "VN")).format(amount) + "đ"

/**
 * Early access to [tag] bought in a popup on Home (or the Account page): the release notes, a
 * VietQR for the transfer, then "Update now" once SePay confirms it ([onUpdate] makes Home check
 * again and show the usual update dialog). The order belongs to the linked account (site/api), so
 * the website shows it too, and a purchase made there opens the release here as well.
 */
@Composable
fun EarlyAccessDialog(
    show: Boolean,
    tag: String,
    version: String,
    onDismissRequest: () -> Unit,
    onUpdate: () -> Unit,
) {
    GlassDialog(
        show = show,
        title = stringResource(R.string.early_title, version),
        onDismissRequest = onDismissRequest,
    ) {
        EarlyAccessContent(tag, version, onDismissRequest, onUpdate)
    }
}

@Composable
private fun EarlyAccessContent(tag: String, version: String, onClose: () -> Unit, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var ui by remember(tag) { mutableStateOf<EarlyUi>(EarlyUi.Loading) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.account_error)
    val closedText = stringResource(R.string.early_closed)
    val notLinked = stringResource(R.string.early_not_linked)
    val notMember = stringResource(R.string.account_not_member)
    val joinText = stringResource(R.string.account_join)

    suspend fun load() {
        error = null
        if (!Account.isLinked()) {
            ui = EarlyUi.Message(notLinked)
            return
        }
        Account.status()
            .onSuccess { status ->
                ui = when {
                    status == null -> EarlyUi.Message(notLinked)
                    !status.member -> EarlyUi.Message(notMember, joinText, status.joinUrl)
                    else -> status.locked.firstOrNull { it.tag == tag }?.let { EarlyUi.Offer(it, status.canBuyEarly) } ?: EarlyUi.Open
                }
            }
            .onFailure { error = errorText }
    }

    fun buy() = scope.launch {
        error = null
        Account.buyEarly(tag)
            .onSuccess { order -> ui = if (order.paid) EarlyUi.Open else EarlyUi.Paying(order) }
            .onFailure { e ->
                when ((e as? OrderException)?.code) {
                    "already_open" -> ui = EarlyUi.Open
                    "sales_closed" -> error = closedText
                    else -> error = errorText
                }
            }
    }

    LaunchedEffect(tag) { load() }

    // While an order waits: fetch its QR once, then ask every 3 s whether the transfer arrived.
    val paying = ui as? EarlyUi.Paying
    LaunchedEffect(paying?.order?.code) {
        val start = paying ?: return@LaunchedEffect
        start.order.pay?.image?.let { image ->
            Account.qrImage(image).onSuccess { bmp -> (ui as? EarlyUi.Paying)?.let { ui = it.copy(qr = bmp) } }
        }
        while (true) {
            delay(3000)
            val now = Account.order(start.order.code).getOrNull() ?: continue
            if (now.paid) {
                ui = EarlyUi.Open
                return@LaunchedEffect
            }
            (ui as? EarlyUi.Paying)?.let { if (it.order != now) ui = it.copy(order = now) }
        }
    }

    // Notes, QR and transfer details can be taller than the screen: the popup scrolls.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let { Text(it, color = colorScheme.error, textAlign = TextAlign.Center) }
            when (val state = ui) {
                EarlyUi.Loading -> Text("…", color = colorScheme.onSurfaceVariantSummary)
                is EarlyUi.Message -> Text(state.text, color = colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
                is EarlyUi.Offer -> OfferBody(state)
                is EarlyUi.Paying -> PayBody(
                    state = state,
                    onSave = { bmp -> saveQr(context, bmp, state.order.code) },
                    onCopy = { label, text -> copy(context, label, text) },
                )
                EarlyUi.Open -> {
                    Text("✓", fontSize = 40.sp, color = colorScheme.primary)
                    Text(stringResource(R.string.early_open, version), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            TextButton(text = stringResource(R.string.close), onClick = onClose, modifier = Modifier.weight(1f))
            val primary: Pair<String, () -> Unit>? = when (val state = ui) {
                is EarlyUi.Offer -> if (state.canBuy) stringResource(R.string.early_buy, vnd(state.release.price)) to { buy(); Unit } else null
                is EarlyUi.Paying -> if (state.order.status == "expired") stringResource(R.string.early_renew) to { buy(); Unit } else null
                is EarlyUi.Message -> state.url?.let { url -> state.button.orEmpty() to { uri.openUri(url) } }
                EarlyUi.Open -> stringResource(R.string.early_update_now) to onUpdate
                EarlyUi.Loading -> null
            }
            if (primary != null) {
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = primary.first,
                    onClick = primary.second,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun OfferBody(state: EarlyUi.Offer) {
    val r = state.release
    val day = remember(r.openAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.openAt)) }
    Text(stringResource(R.string.early_opens, day), color = colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
    Text(
        stringResource(if (state.canBuy) R.string.early_lead else R.string.early_closed, vnd(r.price)),
        textAlign = TextAlign.Center,
    )
    if (r.changelog.isNotBlank()) {
        Text(stringResource(R.string.early_notes), fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        // MarkdownContent scrolls on its own: inside the popup's scroll it needs a bounded height,
        // or Compose throws (infinite max height for a vertical scroll).
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) { MarkdownContent(content = r.changelog, isMarkdown = true) }
    }
}

@Composable
private fun PayBody(state: EarlyUi.Paying, onSave: (Bitmap) -> Unit, onCopy: (String, String) -> Unit) {
    val order = state.order
    val pay = order.pay
    Text(vnd(order.due), fontWeight = FontWeight.Bold, fontSize = 30.sp, color = colorScheme.primary)
    if (order.received > 0) {
        Text(stringResource(R.string.early_short, vnd(order.received), vnd(order.due)), color = colorScheme.error, textAlign = TextAlign.Center)
    }
    if (order.status == "expired") {
        Text(stringResource(R.string.early_expired), color = colorScheme.error)
        return
    }
    Text(stringResource(R.string.early_scan), color = colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, textAlign = TextAlign.Center)
    state.qr?.let { bmp ->
        // White backing: banking apps fail to scan a QR drawn straight over dark glass.
        Box(
            Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(10.dp),
        ) {
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = order.code,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        TextButton(text = stringResource(R.string.early_save_qr), onClick = { onSave(bmp) }, modifier = Modifier.fillMaxWidth())
    }
    if (pay != null) {
        PayRow(stringResource(R.string.early_bank), "${pay.bank} · ${pay.holder}")
        PayRow(stringResource(R.string.early_account), pay.account, onCopy = { onCopy("account", pay.account) })
        PayRow(stringResource(R.string.early_content), pay.content, mono = true, onCopy = { onCopy("content", pay.content) })
        Text(stringResource(R.string.early_content_hint), color = colorScheme.onSurfaceVariantSummary, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
    Text(stringResource(R.string.early_waiting), color = colorScheme.primary, fontSize = 13.sp, textAlign = TextAlign.Center)
}

@Composable
private fun PayRow(label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.Bold, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
        if (onCopy != null) TextButton(text = stringResource(R.string.early_copy), onClick = onCopy)
    }
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.early_copied, Toast.LENGTH_SHORT).show()
}

/** Saves the QR to Pictures/Cam Kernel SU, so a banking app on this phone can scan it from the gallery. */
private fun saveQr(context: Context, bitmap: Bitmap, code: String) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "cam-$code.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Cam Kernel SU")
    }
    val ok = runCatching {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
    }.isSuccess
    Toast.makeText(context, if (ok) R.string.early_saved else R.string.early_save_failed, Toast.LENGTH_SHORT).show()
}
