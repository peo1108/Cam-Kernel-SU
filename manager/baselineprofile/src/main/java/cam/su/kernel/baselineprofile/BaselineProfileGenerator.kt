package cam.su.kernel.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the profile that app/src/release/generated/baselineProfiles holds: launch, then every tab.
 * No separate startup profile: it only orders classes across dex files, and the release APK has
 * one. Run with scripts/device_baseline_profile.sh.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun launchAndTabs() = rule.collect(packageName = targetPackage) {
        pressHome()
        startActivityAndWait()
        visitTabs()
    }
}
