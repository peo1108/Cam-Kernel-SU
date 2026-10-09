package cam.su.kernel.ui.screen.install

import cam.su.kernel.ui.component.glass.GlassDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcelable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import cam.su.kernel.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton as MaterialTextButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

/** An app granted root by the kernel on first boot (`ksud boot-patch --seed pkg:appid`). */
@Parcelize
data class SeedApp(val packageName: String, val appId: Int, val label: String = packageName) : Parcelable {
    fun toSeedArg(): String = "$packageName:$appId"
}

// Must match ksud seed.rs and kernel/policy/pkg_tracker.c.
const val SEED_MAX_APPS = 32
private const val PER_USER_RANGE = 100_000
private val SEED_APPID_RANGE = 10_000..19_999
private val SEED_PACKAGE_REGEX = Regex("[A-Za-z0-9._]{1,255}")

fun isValidSeedPackageName(name: String): Boolean = SEED_PACKAGE_REGEX.matches(name)

private fun ApplicationInfo.toSeedApp(pm: PackageManager): SeedApp? {
    val appId = uid % PER_USER_RANGE
    if (appId !in SEED_APPID_RANGE) return null
    return SeedApp(packageName, appId, loadLabel(pm).toString())
}

/** Returns null when the package is not installed or is not a regular app. */
fun resolveSeedApp(pm: PackageManager, packageName: String): SeedApp? = try {
    pm.getApplicationInfo(packageName, 0).toSeedApp(pm)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun loadSeedCandidates(pm: PackageManager): List<SeedApp> =
    pm.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_HAS_CODE) != 0 }
        .sortedWith(compareBy<ApplicationInfo> { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 })
        .mapNotNull { it.toSeedApp(pm) }
        .distinctBy { it.packageName }

@Stable
private class SeedPickerState(val self: SeedApp, val candidates: List<SeedApp>) {
    var query by mutableStateOf("")
    var input by mutableStateOf("")
    var errorRes by mutableStateOf<Int?>(null)
    private val selected = mutableStateListOf<String>()
    private val extra = mutableStateListOf<SeedApp>()

    val visible: List<SeedApp>
        get() {
            val q = query.trim()
            val all = listOf(self) + extra + candidates.filter { it.packageName != self.packageName }
            return if (q.isEmpty()) all else all.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }

    fun isChecked(app: SeedApp) = app.packageName == self.packageName || app.packageName in selected

    fun toggle(app: SeedApp) {
        if (app.packageName == self.packageName) return
        errorRes = null
        if (app.packageName in selected) {
            selected.remove(app.packageName)
        } else if (selected.size + 1 >= SEED_MAX_APPS) {
            errorRes = R.string.seed_limit
        } else {
            selected.add(app.packageName)
        }
    }

    fun addTyped(pm: PackageManager) {
        val name = input.trim()
        if (!isValidSeedPackageName(name)) {
            errorRes = R.string.seed_invalid_name
            return
        }
        val app = resolveSeedApp(pm, name)
        if (app == null) {
            errorRes = R.string.seed_not_installed
            return
        }
        if (candidates.none { it.packageName == app.packageName } && extra.none { it.packageName == app.packageName }) {
            extra.add(app)
        }
        if (!isChecked(app)) toggle(app)
        if (errorRes == null) input = ""
    }

    fun result(): List<SeedApp> {
        val byName = (candidates + extra).associateBy { it.packageName }
        return listOf(self) + selected.mapNotNull { byName[it] }
    }
}

@Composable
fun SeedPickerDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (List<SeedApp>) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    val state by produceState<SeedPickerState?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { createState(context) }
    }
    val current = state ?: return
    SeedPickerDialogMiuix(current, onDismissRequest, onConfirm)
}

private fun createState(context: Context): SeedPickerState {
    val pm = context.packageManager
    val self = resolveSeedApp(pm, context.packageName) ?: SeedApp(
        context.packageName,
        context.applicationInfo.uid % PER_USER_RANGE,
    )
    return SeedPickerState(self, loadSeedCandidates(pm))
}

@Composable
private fun seedLabel(state: SeedPickerState, app: SeedApp): String =
    if (app.packageName == state.self.packageName) "${app.label} (${stringResource(R.string.seed_this_app)})" else app.label

@Composable
private fun SeedPickerDialogMiuix(
    state: SeedPickerState,
    onDismissRequest: () -> Unit,
    onConfirm: (List<SeedApp>) -> Unit,
) {
    val pm = LocalContext.current.packageManager
    val errorColor = MaterialTheme.colorScheme.error
    GlassDialog(
        show = true,
        title = stringResource(R.string.seed_title),
        summary = stringResource(R.string.seed_summary),
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            Column(modifier = Modifier.heightIn(max = 560.dp)) {
                TextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    label = stringResource(R.string.seed_search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(state.visible, key = { it.packageName }) { app ->
                        CheckboxPreference(
                            title = seedLabel(state, app),
                            summary = app.packageName,
                            insideMargin = PaddingValues(horizontal = 30.dp, vertical = 12.dp),
                            checkboxLocation = CheckboxLocation.End,
                            checked = state.isChecked(app),
                            enabled = app.packageName != state.self.packageName,
                            onCheckedChange = { state.toggle(app) }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    TextField(
                        value = state.input,
                        onValueChange = { state.input = it },
                        label = stringResource(R.string.seed_add_package),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    MiuixTextButton(
                        text = stringResource(R.string.seed_add),
                        onClick = { state.addTyped(pm) }
                    )
                }
                state.errorRes?.let {
                    MiuixText(
                        stringResource(it, SEED_MAX_APPS),
                        color = errorColor,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MiuixTextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    MiuixTextButton(
                        text = stringResource(R.string.confirm),
                        onClick = { onConfirm(state.result()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}
