package cam.su.kernel.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** The Manager's package: scripts/device_baseline_profile.sh passes `targetAppId` (PR builds differ). */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "cam.su.kernel"

// BottomBarDestination labels, Home last so the run ends where it started
private val tabs = listOf("superuser", "module", "features", "files", "settings", "home")

/**
 * Opens every tab and flings its list, so the profile covers what people touch right after launch.
 * Tabs are found by their icon's content description, which is the tab label in the Manager's own
 * language, read from its resources.
 */
fun MacrobenchmarkScope.visitTabs() {
    val res = InstrumentationRegistry.getInstrumentation().context.packageManager
        .getResourcesForApplication(packageName)
    for (name in tabs) {
        val id = res.getIdentifier(name, "string", packageName)
        val tab = device.wait(Until.findObject(By.desc(res.getString(id))), 5_000)
        check(tab != null) { "tab \"${res.getString(id)}\" not on screen" }
        tab.click()
        // the 3D slime on Home never goes idle; a short bound keeps the run moving
        device.waitForIdle(1_000)
        flingPage()
    }
}

private fun MacrobenchmarkScope.flingPage() {
    // the widest scrollable is the page, not the side bar
    val page = device.wait(Until.findObjects(By.scrollable(true)), 3_000)
        ?.maxByOrNull { it.visibleBounds.width() } ?: return
    runCatching {
        page.setGestureMargin(device.displayWidth / 10)
        page.fling(Direction.DOWN)
        page.fling(Direction.UP)
    } // a recomposition can replace the node mid-gesture; the next tab carries on
}
