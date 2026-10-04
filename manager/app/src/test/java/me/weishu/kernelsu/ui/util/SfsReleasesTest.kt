package me.weishu.kernelsu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SfsReleasesTest {

    private fun build(sublevel: Int, tag: String = "v1") = SfsBuild(
        tag = tag,
        fileName = "KernelSU-SFS-android16-5-6.12.$sublevel-32760-susfs-v2.3.0.zip",
        kmi = "android16-6.12",
        kmiTag = "android16-5",
        sublevel = sublevel,
        ksuVersion = 32760,
        susfsVersion = "v2.3.0",
        url = "https://example.invalid/$tag/$sublevel.zip",
        size = 1,
        publishedAt = "2026-10-04T00:00:00Z",
    )

    @Test
    fun exactReleaseIsRecommended() {
        val builds = listOf(build(20), build(30), build(60), build(90))
        assertEquals(30, recommendSfsBuild(builds, 30)?.sublevel)
    }

    @Test
    fun closestReleaseWhenThereIsNoExactOne() {
        val builds = listOf(build(20), build(38), build(60))
        assertEquals(38, recommendSfsBuild(builds, 30)?.sublevel)
        assertEquals(60, recommendSfsBuild(builds, 70)?.sublevel)
    }

    @Test
    fun lowerReleaseWinsATie() {
        val builds = listOf(build(40), build(20))
        assertEquals(20, recommendSfsBuild(builds, 30)?.sublevel)
    }

    @Test
    fun newestReleaseWinsAmongTheSameKernel() {
        // builds are newest release first
        val builds = listOf(build(30, tag = "v2"), build(30, tag = "v1"))
        assertEquals("v2", recommendSfsBuild(builds, 30)?.tag)
    }

    @Test
    fun noBuildsNoRecommendation() {
        assertNull(recommendSfsBuild(emptyList(), 30))
    }

    @Test
    fun readsSublevelAndKmiTag() {
        val release = "6.12.30-android16-5-g1750f757fabe-ab13938768-4k"
        assertEquals(30, sublevelOf(release))
        assertEquals("android16-5", kmiTagOf(release))
        assertEquals(-1, sublevelOf("garbage"))
    }
}
