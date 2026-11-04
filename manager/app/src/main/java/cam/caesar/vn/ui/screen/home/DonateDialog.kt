package cam.su.kernel.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.donate.VietQr
import cam.su.kernel.ui.component.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

// Receiving account for the VietQR code. BIN: the bank's 6-digit NAPAS code (e.g. 970436 Vietcombank).
private const val DONATE_BANK_BIN = "970422"
private const val DONATE_BANK_NAME = "MB Bank"
private const val DONATE_ACCOUNT = "0859855556"
private const val DONATE_HOLDER = "DAO THE VINH"
private const val DONATE_MESSAGE = "Ung ho Cam Kernel SU"

/** VietQR donation code plus a button to the channel the Home support card links to. */
@Composable
fun DonateDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onOpenChannel: () -> Unit,
) {
    GlassDialog(
        show = show,
        title = stringResource(R.string.donate_dialog_title),
        summary = stringResource(R.string.donate_dialog_summary),
        onDismissRequest = onDismissRequest,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (DONATE_BANK_BIN.isNotEmpty() && DONATE_ACCOUNT.isNotEmpty()) {
                val qr = remember {
                    VietQr.bitmap(
                        VietQr.payload(DONATE_BANK_BIN, DONATE_ACCOUNT, message = DONATE_MESSAGE),
                        size = 512,
                    ).asImageBitmap()
                }
                // White backing: banking apps fail to scan a QR drawn straight over dark glass.
                Box(
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(16.dp),
                ) {
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.donate_qr_description),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = DONATE_HOLDER,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "$DONATE_BANK_NAME · $DONATE_ACCOUNT",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.close),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.donate_open_channel),
                    onClick = {
                        onDismissRequest()
                        onOpenChannel()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
