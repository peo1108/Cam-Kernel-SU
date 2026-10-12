package cam.su.kernel.ui.screen.account

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
import cam.su.kernel.account.AccountStatus
import cam.su.kernel.account.Pairing
import cam.su.kernel.account.PollResult
import cam.su.kernel.donate.VietQr
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
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
import java.util.Date

/** What the screen shows: not linked, a pairing code on screen, or the linked account. */
private sealed interface AccountUi {
    data object Loading : AccountUi
    data object NotLinked : AccountUi
    data class Code(val pairing: Pairing, val expired: Boolean = false) : AccountUi
    data class Linked(val status: AccountStatus) : AccountUi
}

/**
 * Settings → Account: link this phone to a caesarvn.xyz account (cam.su.kernel.account.Account).
 * Updates come only to linked member accounts; beta testers are approved on the website.
 */
@Composable
fun AccountScreen() {
    val navigator = LocalNavigator.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<AccountUi>(AccountUi.Loading) }
    var message by remember { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.account_error)
    val membersOnly = stringResource(R.string.account_members_only)
    val sentText = stringResource(R.string.account_tester_sent)

    suspend fun refresh() {
        message = null
        Account.status()
            .onSuccess { status -> ui = if (status == null) AccountUi.NotLinked else AccountUi.Linked(status) }
            .onFailure {
                ui = if (Account.isLinked()) ui.takeIf { it is AccountUi.Linked } ?: AccountUi.NotLinked else AccountUi.NotLinked
                message = errorText
            }
    }

    fun startPairing() = scope.launch {
        message = null
        Account.startPairing()
            .onSuccess { ui = AccountUi.Code(it) }
            .onFailure { message = errorText }
    }

    LaunchedEffect(Unit) { refresh() }

    // While a code is on screen, ask every 2.5 s whether the owner confirmed it on the website.
    val code = ui as? AccountUi.Code
    LaunchedEffect(code?.pairing?.id, code?.expired) {
        val pairing = code?.pairing ?: return@LaunchedEffect
        if (code.expired) return@LaunchedEffect
        val until = System.currentTimeMillis() + pairing.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < until) {
            delay(2500)
            when (Account.poll(pairing.id).getOrNull()) {
                is PollResult.Linked -> {
                    refresh()
                    return@LaunchedEffect
                }
                PollResult.Expired -> break
                else -> Unit
            }
        }
        ui = code.copy(expired = true)
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                SmallTopAppBar(
                    title = stringResource(R.string.account_title),
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
                        AccountUi.Loading -> Unit
                        AccountUi.NotLinked -> NotLinkedCard(onLink = { startPairing() })
                        is AccountUi.Code -> CodeCard(
                            state = state,
                            onOpenWeb = { uri.openUri(state.pairing.url) },
                            onNewCode = { startPairing() },
                        )
                        is AccountUi.Linked -> LinkedCards(
                            status = state.status,
                            onJoin = { uri.openUri(state.status.joinUrl) },
                            onAskTester = {
                                scope.launch {
                                    Account.requestTester()
                                        .onSuccess { r -> message = if (r == "members_only") membersOnly else sentText }
                                        .onFailure { message = errorText }
                                    refresh()
                                }
                            },
                            onRefresh = { scope.launch { refresh() } },
                            onUnlink = {
                                scope.launch {
                                    Account.unlink()
                                    ui = AccountUi.NotLinked
                                }
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
private fun NotLinkedCard(onLink: () -> Unit) {
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.account_unlinked), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(stringResource(R.string.account_link_summary), color = colorScheme.onSurfaceVariantSummary)
            TextButton(
                text = stringResource(R.string.account_link),
                onClick = onLink,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun CodeCard(state: AccountUi.Code, onOpenWeb: () -> Unit, onNewCode: () -> Unit) {
    val qr = remember(state.pairing.url) { VietQr.bitmap(state.pairing.url, 480).asImageBitmap() }
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.account_code_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                state.pairing.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                letterSpacing = 8.sp,
                color = if (state.expired) colorScheme.onSurfaceVariantSummary else colorScheme.primary,
            )
            if (!state.expired) {
                Image(
                    bitmap = qr,
                    contentDescription = state.pairing.url,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(10.dp),
                )
                Text(stringResource(R.string.account_code_hint), color = colorScheme.onSurfaceVariantSummary)
                Text(state.pairing.url.removePrefix("https://"), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                TextButton(
                    text = stringResource(R.string.account_open_web),
                    onClick = onOpenWeb,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Text(stringResource(R.string.account_waiting), color = colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            } else {
                Text(stringResource(R.string.account_expired), color = colorScheme.error)
                TextButton(
                    text = stringResource(R.string.account_new_code),
                    onClick = onNewCode,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun LinkedCards(
    status: AccountStatus,
    onJoin: () -> Unit,
    onAskTester: () -> Unit,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
) {
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status.email, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (status.member) {
                Text("✦ " + stringResource(R.string.account_member), color = colorScheme.primary, fontWeight = FontWeight.Bold)
            } else {
                Text(stringResource(R.string.account_not_member), color = colorScheme.onSurfaceVariantSummary)
                TextButton(
                    text = stringResource(R.string.account_join),
                    onClick = onJoin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    if (status.member) {
        GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_tester), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    stringResource(
                        when (status.tester) {
                            "approved" -> R.string.account_tester_approved
                            "pending" -> R.string.account_tester_pending
                            "rejected" -> R.string.account_tester_rejected
                            else -> R.string.account_tester_none
                        },
                    ),
                    color = if (status.tester == "approved") colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                )
                if (status.tester == "none" || status.tester == "rejected") {
                    TextButton(text = stringResource(R.string.account_tester_ask), onClick = onAskTester, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (status.locked.isNotEmpty()) {
            GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.account_waiting_releases), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    status.locked.take(5).forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.version, fontFamily = FontFamily.Monospace)
                            Text(
                                stringResource(R.string.account_opens_on, date.format(Date(r.openAt))),
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    TextButton(text = stringResource(R.string.account_get_early), onClick = onJoin, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(R.string.account_refresh), onClick = onRefresh, modifier = Modifier.fillMaxWidth())
            TextButton(text = stringResource(R.string.account_unlink), onClick = onUnlink, modifier = Modifier.fillMaxWidth())
        }
    }
}
