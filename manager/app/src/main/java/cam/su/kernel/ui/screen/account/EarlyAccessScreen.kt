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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.account.Account
import cam.su.kernel.account.LockedRelease
import cam.su.kernel.account.Order
import cam.su.kernel.account.OrderException
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.component.markdown.MarkdownContent
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.update.UpdateSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** What the screen shows for one release. */
private sealed interface EarlyUi {
    data object Loading : EarlyUi
    data object NotLinked : EarlyUi
    data object NotMember : EarlyUi
    /** Waiting days still running: notes, open date, price, the buy button. */
    data class Offer(val release: LockedRelease, val canBuy: Boolean) : EarlyUi
    /** An order waits for the transfer; [qr] once SePay's image arrived. */
    data class Paying(val release: LockedRelease?, val order: Order, val qr: Bitmap? = null) : EarlyUi
    /** Paid here or on the website, or the release opened meanwhile: the update can go ahead. */
    data object Open : EarlyUi
}

private fun vnd(amount: Long): String = NumberFormat.getInstance(Locale("vi", "VN")).format(amount) + "đ"

/**
 * Early access to [tag] bought in the app: the release notes, a VietQR for the transfer, and the
 * update once SePay confirms it. The order belongs to the linked account (site/api), so the
 * website shows it too and a purchase made there opens the release here as well.
 */
@Composable
fun EarlyAccessScreen(tag: String, version: String) {
    val navigator = LocalNavigator.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<EarlyUi>(EarlyUi.Loading) }
    var message by remember { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.account_error)
    val closedText = stringResource(R.string.early_closed)

    suspend fun load() {
        message = null
        if (!Account.isLinked()) {
            ui = EarlyUi.NotLinked
            return
        }
        Account.status()
            .onSuccess { status ->
                ui = when {
                    status == null -> EarlyUi.NotLinked
                    !status.member -> EarlyUi.NotMember
                    else -> status.locked.firstOrNull { it.tag == tag }?.let { EarlyUi.Offer(it, status.canBuyEarly) } ?: EarlyUi.Open
                }
            }
            .onFailure { message = errorText }
    }

    fun buy(release: LockedRelease?) = scope.launch {
        message = null
        Account.buyEarly(tag)
            .onSuccess { order -> ui = if (order.paid) EarlyUi.Open else EarlyUi.Paying(release, order) }
            .onFailure { e ->
                when ((e as? OrderException)?.code) {
                    "already_open" -> ui = EarlyUi.Open
                    "sales_closed" -> message = closedText
                    else -> message = errorText
                }
            }
    }

    LaunchedEffect(Unit) { load() }

    // While an order waits: fetch its QR once, then ask every 3 s whether the transfer arrived.
    val paying = ui as? EarlyUi.Paying
    LaunchedEffect(paying?.order?.code) {
        val start = paying ?: return@LaunchedEffect
        val image = start.order.pay?.image
        if (image != null && start.qr == null) {
            Account.qrImage(image).onSuccess { bmp -> (ui as? EarlyUi.Paying)?.let { ui = it.copy(qr = bmp) } }
        }
        while (true) {
            delay(3000)
            val now = Account.order(start.order.code).getOrNull() ?: continue
            when {
                now.paid -> {
                    ui = EarlyUi.Open
                    return@LaunchedEffect
                }
                else -> (ui as? EarlyUi.Paying)?.let { if (it.order != now) ui = it.copy(order = now) }
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                SmallTopAppBar(
                    title = stringResource(R.string.early_title, version),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        GlassIconButton(onClick = { navigator.pop() }) {
                            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                            Icon(
                                modifier = Modifier.graphicsLayer { if (rtl) scaleX = -1f },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    message?.let {
                        GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                            Text(it, color = colorScheme.error, modifier = Modifier.padding(16.dp))
                        }
                    }
                    when (val state = ui) {
                        EarlyUi.Loading -> Unit
                        EarlyUi.NotLinked -> InfoCard(
                            text = stringResource(R.string.early_not_linked),
                            button = stringResource(R.string.account_link),
                            onClick = { navigator.push(Route.Account) },
                        )
                        EarlyUi.NotMember -> InfoCard(
                            text = stringResource(R.string.account_not_member),
                            button = stringResource(R.string.account_join),
                            onClick = { uri.openUri("${Account.SITE}/thanh-vien") },
                        )
                        is EarlyUi.Offer -> OfferCards(state, onBuy = { buy(state.release) })
                        is EarlyUi.Paying -> PayCards(
                            state = state,
                            onSave = { bmp -> saveQr(context, bmp, state.order.code) },
                            onCopy = { label, text -> copy(context, label, text) },
                            onRenew = { buy(state.release) },
                        )
                        EarlyUi.Open -> OpenCard(
                            version = version,
                            onUpdate = {
                                // Home checks again, finds the release open and shows the update.
                                UpdateSignal.request()
                                navigator.popUntil { it is Route.Main }
                            },
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String, button: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, color = colorScheme.onSurfaceVariantSummary)
            TextButton(text = button, onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun OfferCards(state: EarlyUi.Offer, onBuy: () -> Unit) {
    val r = state.release
    val day = remember(r.openAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.openAt)) }
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.early_opens, day), color = colorScheme.onSurfaceVariantSummary)
            if (state.canBuy) {
                Text(stringResource(R.string.early_lead, vnd(r.price)))
                TextButton(
                    text = stringResource(R.string.early_buy, vnd(r.price)),
                    onClick = onBuy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                Text(stringResource(R.string.early_closed), color = colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    if (r.changelog.isNotBlank()) {
        GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.early_notes), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                MarkdownContent(content = r.changelog, isMarkdown = true)
            }
        }
    }
}

@Composable
private fun PayCards(
    state: EarlyUi.Paying,
    onSave: (Bitmap) -> Unit,
    onCopy: (String, String) -> Unit,
    onRenew: () -> Unit,
) {
    val order = state.order
    val pay = order.pay
    val expired = order.status == "expired"
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(vnd(order.due), fontWeight = FontWeight.Bold, fontSize = 32.sp, color = colorScheme.primary)
            if (order.received > 0) {
                Text(stringResource(R.string.early_short, vnd(order.received), vnd(order.due)), color = colorScheme.error)
            }
            if (expired) {
                Text(stringResource(R.string.early_expired), color = colorScheme.error)
                TextButton(text = stringResource(R.string.early_renew), onClick = onRenew, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            } else {
                Text(stringResource(R.string.early_scan), color = colorScheme.onSurfaceVariantSummary)
                state.qr?.let { bmp ->
                    Image(
                        bitmap = remember(bmp) { bmp.asImageBitmap() },
                        contentDescription = order.code,
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(8.dp),
                    )
                    TextButton(text = stringResource(R.string.early_save_qr), onClick = { onSave(bmp) }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.early_waiting), color = colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            }
        }
    }
    if (pay != null && !expired) {
        GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.early_manual), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                PayRow(stringResource(R.string.early_bank), pay.bank)
                PayRow(stringResource(R.string.early_account), pay.account, onCopy = { onCopy("account", pay.account) })
                PayRow(stringResource(R.string.early_holder), pay.holder)
                PayRow(stringResource(R.string.early_amount), vnd(order.due), onCopy = { onCopy("amount", order.due.toString()) })
                PayRow(stringResource(R.string.early_content), pay.content, mono = true, onCopy = { onCopy("content", pay.content) })
                Text(stringResource(R.string.early_content_hint), color = colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PayRow(label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            Text(value, fontWeight = FontWeight.Bold, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
        if (onCopy != null) TextButton(text = stringResource(R.string.early_copy), onClick = onCopy)
    }
}

@Composable
private fun OpenCard(version: String, onUpdate: () -> Unit) {
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("✓", fontSize = 40.sp, color = colorScheme.primary)
            Text(stringResource(R.string.early_open, version), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextButton(
                text = stringResource(R.string.early_update_now),
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
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
