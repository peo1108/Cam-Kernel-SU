package cam.su.kernel.ui.component.hero

import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import cam.su.kernel.ui.component.AppIconImage
import cam.su.kernel.ui.component.glass.GlassPageAnchor
import cam.su.kernel.ui.component.glass.LocalGlassPageAnchor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import kotlin.math.roundToInt

/** The two things that fly: the app icon and its label. */
enum class HeroPart { Icon, Label }

internal class HeroFlight(val key: Any, val packageInfo: PackageInfo, val label: String)

internal class HeroFrame(val rect: Rect, val alpha: Float)

// an element and the page it sits on: the flight runs between resting places, so a page sliding
// in or the covered page's parallax never bends the path
private class HeroAnchor(val coordinates: LayoutCoordinates, val page: GlassPageAnchor?) {
    val isAttached get() = coordinates.isAttached

    // where the element sits once its page is at rest (pages rest at the overlay's origin)
    fun restingRect(overlay: LayoutCoordinates): Rect {
        val pageCoordinates = page?.coordinates?.takeIf { it.isAttached }
            ?: return overlay.localBoundingBoxOf(coordinates, clipBounds = false)
        return pageCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
    }
}

/**
 * The one Superuser → App Profile icon flight (docs/superpowers/specs/2026-10-16-superuser-hero-design.md).
 * Sources and targets register their [LayoutCoordinates]; the overlay reads them, and the App
 * Profile page's transition depth, while drawing, so nothing recomposes during a flight.
 */
@Stable
class HeroState(private val animationsOff: Boolean) {
    internal var flight by mutableStateOf<HeroFlight?>(null)
        private set

    // bumped when the flight's own source/target attaches or leaves, so draws that saw nothing
    // to read yet run again; positions themselves are read live and need no bump
    private var version by mutableIntStateOf(0)
    private val sources = HashMap<Pair<Any, HeroPart>, HeroAnchor>()
    private val targets = HashMap<Pair<Any, HeroPart>, HeroAnchor>()
    private val targetNavs = HashMap<Any, NavTransitionScope>()

    /**
     * Starts a flight from [key]'s source to its App Profile header, before the page is pushed.
     * False (no flight) when the source icon is mostly off screen or animations are off; a flight
     * already running for [key] (a double tap) is kept.
     */
    fun launch(key: Any, packageInfo: PackageInfo, label: String): Boolean {
        if (flight?.key == key) return true
        val icon = sources[key to HeroPart.Icon]?.takeIf { it.isAttached }?.coordinates ?: return false
        val full = Rect(icon.positionInWindow(), icon.size.toSize())
        if (!heroCanLaunch(animationsOff, visibleFraction(full, icon.boundsInWindow()))) return false
        flight = HeroFlight(key, packageInfo, label)
        return true
    }

    /** Ends [key]'s flight; App Profile calls it when its page leaves. */
    fun end(key: Any) {
        if (flight?.key == key) flight = null
    }

    internal fun phase(key: Any): HeroPhase {
        version
        val current = flight
        if (current == null || current.key != key) return HeroPhase.Gone
        val nav = targetNavs[key] ?: return HeroPhase.Gone
        return heroPhase(
            depth = nav.relativeDepth,
            running = nav.isRunning,
            sourceAttached = sources[key to HeroPart.Icon]?.isAttached == true,
            targetAttached = targets[key to HeroPart.Icon]?.isAttached == true,
        )
    }

    internal fun register(key: Any, part: HeroPart, coordinates: LayoutCoordinates, page: GlassPageAnchor?, source: Boolean) {
        val map = if (source) sources else targets
        val known = map[key to part]
        if (known?.coordinates === coordinates && known.page === page) return
        map[key to part] = HeroAnchor(coordinates, page)
        if (flight?.key == key) version++
    }

    internal fun unregister(key: Any, part: HeroPart, source: Boolean) {
        val map = if (source) sources else targets
        if (map.remove(key to part) != null && flight?.key == key) version++
    }

    internal fun attachNav(key: Any, nav: NavTransitionScope) {
        if (targetNavs.put(key, nav) !== nav && flight?.key == key) version++
    }

    internal fun detachNav(key: Any, nav: NavTransitionScope) {
        if (targetNavs[key] === nav) targetNavs.remove(key)
    }

    // where [part]'s copy is drawn this frame, in [overlay]'s space; null = not drawn
    internal fun frame(key: Any, part: HeroPart, overlay: LayoutCoordinates?): HeroFrame? {
        val phase = phase(key)
        if (overlay == null || !overlay.isAttached) return null
        val nav = targetNavs[key] ?: return null
        val target = targets[key to part]?.takeIf { it.isAttached } ?: return null
        val targetRect = target.restingRect(overlay)
        val t = heroProgress(nav.relativeDepth)
        return when (phase) {
            HeroPhase.Flying -> {
                val source = sources[key to part]?.takeIf { it.isAttached } ?: return null
                HeroFrame(lerp(source.restingRect(overlay), targetRect, t), 1f)
            }
            HeroPhase.FadingOut -> HeroFrame(targetRect, t)
            HeroPhase.Docked, HeroPhase.Gone -> null
        }
    }
}

/** The app's [HeroState]; null outside [CamActivity]'s navigation, where nothing flies. */
val LocalHero: ProvidableCompositionLocal<HeroState?> = staticCompositionLocalOf { null }

@Composable
fun rememberHeroState(): HeroState {
    val context = LocalContext.current
    return remember {
        HeroState(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    }
}

/** Marks the Superuser card's icon or label as where a flight starts and ends. */
@Composable
fun Modifier.heroSource(key: Any, part: HeroPart): Modifier {
    val hero = LocalHero.current ?: return this
    val page = LocalGlassPageAnchor.current
    DisposableEffect(hero, key, part) {
        onDispose { hero.unregister(key, part, source = true) }
    }
    return this
        .onGloballyPositioned { hero.register(key, part, it, page, source = true) }
        .graphicsLayer { alpha = if (hero.phase(key).hidesSource()) 0f else 1f }
}

/** Marks the App Profile header's icon or label as where a flight lands. */
@Composable
fun Modifier.heroTarget(key: Any, part: HeroPart): Modifier {
    val hero = LocalHero.current ?: return this
    val nav: NavTransitionScope = LocalNavTransitionScope.current
    val page = LocalGlassPageAnchor.current
    DisposableEffect(hero, key, part, nav) {
        if (part == HeroPart.Icon) hero.attachNav(key, nav)
        onDispose {
            hero.unregister(key, part, source = false)
            if (part == HeroPart.Icon) hero.detachNav(key, nav)
        }
    }
    return this
        .onGloballyPositioned { hero.register(key, part, it, page, source = false) }
        .graphicsLayer { alpha = if (hero.phase(key).hidesTarget()) 0f else 1f }
}

private class OverlayAnchor {
    var coordinates: LayoutCoordinates? = null
}

/** Draws the flying icon and label over every page; takes no touches. */
@Composable
fun HeroOverlay(state: HeroState, modifier: Modifier = Modifier) {
    val flight = state.flight ?: return
    val anchor = remember { OverlayAnchor() }
    Box(modifier = modifier.fillMaxSize().onPlaced { anchor.coordinates = it }) {
        AppIconImage(
            packageInfo = flight.packageInfo,
            label = flight.label,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val frame = state.frame(flight.key, HeroPart.Icon, anchor.coordinates)
                    if (frame == null || size.width <= 0f) {
                        alpha = 0f
                    } else {
                        translationX = frame.rect.left
                        translationY = frame.rect.top
                        scaleX = frame.rect.width / size.width
                        scaleY = frame.rect.height / size.height
                        alpha = frame.alpha
                    }
                },
        )
        Text(
            text = flight.label,
            fontWeight = FontWeight(550),
            color = colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                // card and header labels differ in width; the copy morphs between them
                .layout { measurable, _ ->
                    val width = state.frame(flight.key, HeroPart.Label, anchor.coordinates)
                        ?.rect?.width?.roundToInt()?.coerceAtLeast(0) ?: 0
                    val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val frame = state.frame(flight.key, HeroPart.Label, anchor.coordinates)
                    if (frame == null) {
                        alpha = 0f
                    } else {
                        translationX = frame.rect.left
                        translationY = frame.rect.top
                        alpha = frame.alpha
                    }
                },
        )
    }
}
