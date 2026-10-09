package cam.su.kernel.ui.screen.colorpalette

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cam.su.kernel.R
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.util.AppIdentity
import cam.su.kernel.ui.util.AppIdentity.Preset
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val PREVIEW_PX = 192

/** Theme-page card that changes the Manager's own launcher name and icon directly (needs root). */
@Composable
fun AppIdentitySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presetIcons = remember {
        Preset.entries.associateWith {
            AppIdentity.iconBitmap(context, AppIdentity.Icon.OfPreset(it), PREVIEW_PX)?.asImageBitmap()
        }
    }
    val defaultIcon = remember {
        AppIdentity.iconBitmap(context, AppIdentity.Icon.Default, PREVIEW_PX)?.asImageBitmap()
    }
    val defaultName = stringResource(R.string.app_name)

    var name by rememberSaveable { mutableStateOf(AppIdentity.savedName(context) ?: defaultName) }
    var icon by remember { mutableStateOf(AppIdentity.savedIcon(context)) }
    var customVersion by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }

    val customIcon by produceState<ImageBitmap?>(null, customVersion) {
        value = withContext(Dispatchers.IO) {
            AppIdentity.iconBitmap(context, AppIdentity.Icon.Custom, PREVIEW_PX)?.asImageBitmap()
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (withContext(Dispatchers.IO) { AppIdentity.importIcon(context, uri) }) {
                customVersion++
                icon = AppIdentity.Icon.Custom
            } else {
                Toast.makeText(context, R.string.app_identity_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    GlassCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = stringResource(R.string.app_identity),
            summary = stringResource(R.string.app_identity_summary),
            startAction = {
                Icon(
                    Icons.Rounded.Apps,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = colorScheme.onBackground,
                )
            },
        )

        // Preview: how the drawer entry will look once applied.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val previewIcon = when (val i = icon) {
                AppIdentity.Icon.Default -> defaultIcon
                is AppIdentity.Icon.OfPreset -> presetIcons[i.preset]
                AppIdentity.Icon.Custom -> customIcon
            }
            Box(modifier = Modifier.size(64.dp)) {
                previewIcon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
            }
            Text(
                text = name.ifBlank { defaultName },
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.app_identity_name),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NameChip(defaultName, selected = name == defaultName, onClick = { name = defaultName })
            Preset.entries.forEach { preset ->
                val label = stringResource(preset.label)
                NameChip(label, selected = name == label, onClick = { name = label })
            }
        }

        Text(
            text = stringResource(R.string.app_identity_icon),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconTile(
                bitmap = defaultIcon,
                label = stringResource(R.string.app_identity_default),
                selected = icon == AppIdentity.Icon.Default,
                onClick = { icon = AppIdentity.Icon.Default },
            )
            Preset.entries.forEach { preset ->
                IconTile(
                    bitmap = presetIcons[preset],
                    label = stringResource(preset.label),
                    selected = icon == AppIdentity.Icon.OfPreset(preset),
                    onClick = { icon = AppIdentity.Icon.OfPreset(preset) },
                )
            }
            customIcon?.let {
                IconTile(
                    bitmap = it,
                    label = stringResource(R.string.app_identity_custom),
                    selected = icon == AppIdentity.Icon.Custom,
                    onClick = { icon = AppIdentity.Icon.Custom },
                )
            }
            IconTile(
                bitmap = null,
                placeholder = Icons.Rounded.AddPhotoAlternate,
                label = stringResource(R.string.app_identity_gallery),
                selected = false,
                onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }

        TextButton(
            text = stringResource(if (applying) R.string.app_identity_applying else R.string.app_identity_apply),
            enabled = !applying && (icon != AppIdentity.Icon.Custom || customIcon != null),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
                applying = true
                val chosenName = name
                val chosenIcon = icon
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { AppIdentity.apply(context, chosenName, chosenIcon) }
                    applying = false
                    Toast.makeText(
                        context,
                        if (ok) R.string.app_identity_applied else R.string.app_identity_apply_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun IconTile(
    bitmap: ImageBitmap?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    placeholder: ImageVector? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .then(if (selected) Modifier.border(2.dp, colorScheme.primary, shape) else Modifier)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = label, modifier = Modifier.fillMaxSize())
            } else if (placeholder != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(placeholder, contentDescription = label, tint = colorScheme.onSecondaryContainer)
                }
            }
        }
        Text(
            text = label,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) colorScheme.primary else colorScheme.onBackground,
        )
    }
}

@Composable
private fun NameChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colorScheme.primary else colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        fontSize = 13.sp,
        color = if (selected) colorScheme.onPrimary else colorScheme.onSecondaryContainer,
    )
}
