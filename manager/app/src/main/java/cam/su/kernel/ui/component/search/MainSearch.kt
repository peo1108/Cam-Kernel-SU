package cam.su.kernel.ui.component.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.ui.component.liquid.LiquidSpecular
import cam.su.kernel.ui.component.liquid.lens
import cam.su.kernel.ui.component.liquid.rememberGravityRotatedHighlight
import cam.su.kernel.ui.component.liquid.vibrancy
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Search shared by the main pages: a round button next to the floating bottom bar
 * opens a search field docked above the keyboard, and the current page filters its
 * own list by [query] while it is [active].
 */
@Stable
class MainSearchState {
    var active by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    fun open() {
        active = true
    }

    fun close() {
        active = false
        query = ""
    }
}

val LocalMainSearch = staticCompositionLocalOf { MainSearchState() }

/** Pages of the main pager that filter their list from the shared search. */
object SearchablePages {
    const val SUPERUSER = 1
    const val MODULE = 2
    fun contains(page: Int) = page == SUPERUSER || page == MODULE
}

/** The query a page should filter by: the shared one when it is the page being searched. */
@Composable
fun mainSearchQueryFor(isCurrentPage: Boolean): String {
    val search = LocalMainSearch.current
    return if (isCurrentPage && search.active) search.query else ""
}

/** The liquid glass surface of the floating bottom bar, for the search button and field. */
@Composable
private fun Modifier.liquidGlass(backdrop: Backdrop, isBlurEnabled: Boolean, shape: Shape): Modifier {
    val isInDark = isInDarkTheme()
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = if (isBlurEnabled) surfaceContainer.copy(0.4f) else surfaceContainer
    val highlight = rememberGravityRotatedHighlight(LiquidSpecular, extraDegrees = -45f)
    return this
        .dropShadow(
            shape = shape,
            shadow = Shadow(radius = 10.dp, color = Color.Black, alpha = if (isInDark) 0.2f else 0.1f),
        )
        .then(
            if (isBlurEnabled) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        padding = maxOf(padding, 40.dp.toPx())
                        vibrancy()
                        blur(4.dp.toPx(), 4.dp.toPx())
                        lens(refractionHeight = 24.dp.toPx(), refractionAmount = 24.dp.toPx())
                    },
                    highlight = { highlight.value.copy(alpha = 0.75f) },
                    onDrawSurface = { drawRect(containerColor) },
                )
            } else {
                Modifier.background(containerColor, shape)
            }
        )
}

/** Round glass button, as tall as the floating bottom bar. */
@Composable
fun GlassCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    backdrop: Backdrop,
    isBlurEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .liquidGlass(backdrop, isBlurEnabled, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * The open search: a glass pill with the field, and a round button that closes it,
 * placed by the caller above the keyboard (imePadding).
 */
@Composable
fun SearchDock(
    state: MainSearchState,
    backdrop: Backdrop,
    isBlurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .liquidGlass(backdrop, isBlurEnabled, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        color = colors.onSurfaceVariantSummary,
                        fontSize = 17.sp,
                    )
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 17.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (state.query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Cancel,
                    contentDescription = stringResource(R.string.search_clear),
                    tint = colors.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { state.query = "" },
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        GlassCircleButton(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close),
            backdrop = backdrop,
            isBlurEnabled = isBlurEnabled,
            onClick = {
                keyboard?.hide()
                state.close()
            },
            size = 52.dp,
        )
    }
}
