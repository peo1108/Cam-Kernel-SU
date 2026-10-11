package cam.su.kernel.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateParserTest {

    private fun asset(name: String, size: Long = 1000, digest: String? = null) = JSONObject().apply {
        put("name", name)
        put("size", size)
        put("browser_download_url", "https://example.com/$name")
        if (digest != null) put("digest", digest)
    }

    private fun apk(version: String, code: Long, digest: String? = null) =
        asset("SU_Kernel_${version}_$code-release.apk", digest = digest)

    private fun release(
        tag: String,
        vararg assets: JSONObject,
        body: String = "- note",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = JSONObject().apply {
        put("tag_name", tag)
        put("body", body)
        put("draft", draft)
        put("prerelease", prerelease)
        put("assets", JSONArray(assets.toList()))
    }

    private fun list(vararg releases: JSONObject) = JSONArray(releases.toList()).toString()

    @Test
    fun picksHighestCamRelease() {
        val json = list(
            release("cam-v3.0.1", apk("3.0.1", 32900)),
            release("cam-v3.0.2", apk("3.0.2", 32950)),
        )
        val info = parseReleases(json, 32800)!!
        assertEquals("3.0.2", info.versionName)
        assertEquals(32950L, info.versionCode)
        assertEquals("https://example.com/SU_Kernel_3.0.2_32950-release.apk", info.apkUrl)
    }

    @Test
    fun readsTheCiApkName() {
        // the name Build Manager really produces: <app name>_<version name>_<version code>-release.apk
        val json = list(release("cam-v3.0.1", asset("SU_Kernel_v3.3.0-187-g21536a17_32788-release.apk")))
        assertEquals(32788L, parseReleases(json, 32700)!!.versionCode)
    }

    @Test
    fun ignoresNonCamTags() {
        val json = list(
            release("sfs-32787", apk("3.0.1", 32900)),
            release("v3.3.0", apk("3.3.0", 33000)),
        )
        assertNull(parseReleases(json, 32800))
    }

    @Test
    fun ignoresDraftAndPrerelease() {
        val json = list(
            release("cam-v3.0.1", apk("3.0.1", 32900), draft = true),
            release("cam-v3.0.2", apk("3.0.2", 32950), prerelease = true),
        )
        assertNull(parseReleases(json, 32800))
    }

    @Test
    fun betaChannelTakesPrereleasesButNeverDrafts() {
        val json = list(
            release("cam-v3.0.1", apk("3.0.1", 32900), draft = true),
            release("cam-v3.1.0-beta.1", apk("3.1.0-beta.1", 32950), prerelease = true),
        )
        val info = parseReleases(json, 32800, includePrereleases = true)!!
        assertEquals("3.1.0-beta.1", info.versionName)
        assertEquals(32950L, info.versionCode)
    }

    @Test
    fun skippedReleasesAddTheirNotesNewestFirst() {
        val json = list(
            release("cam-v3.0.7", apk("3.0.7", 32800), body = "- seven"),
            release("cam-v3.0.9", apk("3.0.9", 32830), body = "- nine\r\n"),
            release("cam-v3.0.8", apk("3.0.8", 32810), body = "- eight"),
            release("cam-v3.0.6", apk("3.0.6", 32790), body = "- six"),
        )
        val info = parseReleases(json, 32790)!!
        assertEquals("3.0.9", info.versionName)
        assertEquals("## 3.0.9\n\n- nine\n\n## 3.0.8\n\n- eight\n\n## 3.0.7\n\n- seven", info.changelog)
        assertEquals("nine", info.summary)
        // one release ahead: its notes as they are, without a heading
        assertEquals("- nine\r\n", parseReleases(json, 32810)!!.changelog)
    }

    @Test
    fun skippedNotesLeaveOutPrereleasesOffTheBetaChannel() {
        val json = list(
            release("cam-v3.1.0", apk("3.1.0", 32900), body = "- final"),
            release("cam-v3.1.0-beta.2", apk("3.1.0-beta.2", 32880), body = "- beta two", prerelease = true),
            release("cam-v3.1.0-beta.1", apk("3.1.0-beta.1", 32860), body = "- beta one", prerelease = true),
            release("cam-v3.0.9", apk("3.0.9", 32830), body = "- nine"),
        )
        assertEquals("## 3.1.0\n\n- final\n\n## 3.0.9\n\n- nine", parseReleases(json, 32800)!!.changelog)
        // on the beta channel too: the betas share 3.1.0's entry, so only the final one is shown
        assertEquals(
            "## 3.1.0\n\n- final\n\n## 3.0.9\n\n- nine",
            parseReleases(json, 32800, includePrereleases = true)!!.changelog,
        )
        // before the final: the newest beta stands for 3.1.0
        val betas = list(
            release("cam-v3.1.0-beta.2", apk("3.1.0-beta.2", 32880), body = "- beta two", prerelease = true),
            release("cam-v3.1.0-beta.1", apk("3.1.0-beta.1", 32860), body = "- beta one", prerelease = true),
            release("cam-v3.0.9", apk("3.0.9", 32830), body = "- nine"),
        )
        assertEquals(
            "## 3.1.0-beta.2\n\n- beta two\n\n## 3.0.9\n\n- nine",
            parseReleases(betas, 32800, includePrereleases = true)!!.changelog,
        )
    }

    @Test
    fun ignoresReleaseWithoutMatchingApk() {
        val json = list(release("cam-v3.0.1", asset("camd-aarch64-linux-android")))
        assertNull(parseReleases(json, 32800))
    }

    @Test
    fun notNewerReturnsNull() {
        assertNull(parseReleases(list(release("cam-v3.0.1", apk("3.0.1", 32900))), 32900))
        assertNull(parseReleases(list(release("cam-v3.0.1", apk("3.0.1", 32900))), 33000))
    }

    @Test
    fun readsDigestAndSize() {
        val withDigest = JSONObject(apk("3.0.1", 32900, digest = "sha256:ABCDEF0123").toString()).put("size", 1234)
        val info = parseReleases(list(release("cam-v3.0.1", withDigest)), 32800)!!
        assertEquals("abcdef0123", info.sha256)
        assertEquals(1234L, info.apkSize)

        val noDigest = parseReleases(list(release("cam-v3.0.1", apk("3.0.1", 32900))), 32800)!!
        assertNull(noDigest.sha256)
    }

    @Test
    fun rateLimitObjectReturnsNull() {
        assertNull(parseReleases("""{"message":"API rate limit exceeded for 1.2.3.4."}""", 32800))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(parseReleases("not json", 32800))
    }

    @Test
    fun failedCheckKeepsTheKnownUpdate() {
        val known = parseReleases(list(release("cam-v3.0.1", apk("3.0.1", 32900))), 32800)
        // rate limited: the card must not disappear
        assertEquals(known, keepKnownUpdate(known, Result.failure(java.io.IOException("HTTP 403"))))
        // a successful "nothing newer" answer does clear it
        assertNull(keepKnownUpdate(known, Result.success(null)))
    }

    @Test
    fun summaryIsFirstBullet() {
        val info = parseReleases(
            list(release("cam-v3.0.1", apk("3.0.1", 32900), body = "### Tính năng mới\r\n- Thêm OTA\n- Khác")),
            32800,
        )!!
        assertEquals("Thêm OTA", info.summary)
        val noBullet = parseReleases(list(release("cam-v3.0.1", apk("3.0.1", 32900), body = "Chỉ có chữ")), 32800)!!
        assertNull(noBullet.summary)
    }
}
