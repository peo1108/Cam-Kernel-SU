package cam.su.kernel.hiding

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleRiskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val block = ModuleRisk(
        level = ModuleRisk.Level.Block,
        ids = listOf("evil_menu"),
        patterns = listOf("zygisk floating overlay menu"),
        reasonVi = "mã độc",
        reasonEn = "malware",
        source = "test",
    )
    private val warn = ModuleRisk(
        level = ModuleRisk.Level.Warn,
        ids = emptyList(),
        patterns = listOf("sketchy tweak"),
        reasonVi = "đáng ngờ",
        reasonEn = "sketchy",
    )
    private val scanner = ModuleRiskScanner(listOf(warn, block))

    private fun zip(vararg files: Pair<String, ByteArray>): File =
        tmp.newFile("m${System.nanoTime()}.zip").also { out ->
            ZipOutputStream(out.outputStream()).use { zip ->
                files.forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body)
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun patternMatchesAcrossCaseAndPunctuation() {
        assertEquals(block, scanner.matchText("#!/system/bin/sh\necho Zygisk-Floating_Overlay  MENU loaded"))
        assertEquals(block, scanner.matchText("ｚｙｇｉｓｋ floating overlay menu")) // NFKC folds full-width letters
    }

    @Test
    fun wordsApartOrInsideOtherWordsDoNotMatch() {
        assertNull(scanner.matchText("zygisk floating and an overlay menu"))
        assertNull(scanner.matchText("zygiskfloating overlay menu"))
        assertNull(scanner.matchText(""))
    }

    @Test
    fun blockWinsOverWarn() {
        assertEquals(block, scanner.matchText("sketchy tweak with zygisk floating overlay menu"))
        assertEquals(warn, scanner.matchText("a sketchy tweak"))
    }

    @Test
    fun installedModuleMatchesByIdOrProp() {
        assertEquals(block, scanner.matchModule("evil_menu", "Nice name", "someone")?.risk)
        assertEquals(warn, scanner.matchModule("other", "Sketchy Tweak", "x")?.risk)
        assertNull(scanner.matchModule("evil_menu2", "Nice", "someone"))
    }

    @Test
    fun zipMatchesPropIdThenFiles() {
        val byId = zip("module.prop" to "id=evil_menu\nname=Clean\n".toByteArray())
        assertEquals(ModuleRiskHit(block, "module.prop"), scanner.scanZip(byId))

        val byFile = zip(
            "module.prop" to "id=fine\nname=Fine\n".toByteArray(),
            "service.sh" to "start zygisk_floating_overlay_menu &".toByteArray(),
        )
        assertEquals(ModuleRiskHit(block, "service.sh"), scanner.scanZip(byFile))

        val clean = zip("module.prop" to "id=fine\nname=Fine\n".toByteArray(), "a.sh" to "echo hi".toByteArray())
        assertNull(scanner.scanZip(clean))
    }

    @Test
    fun brokenZipOrNoRulesFindNothing() {
        val broken = tmp.newFile("broken.zip").apply { writeText("not a zip") }
        assertNull(scanner.scanZip(broken))
        assertNull(ModuleRiskScanner(emptyList()).scanZip(zip("a" to "zygisk floating overlay menu".toByteArray())))
    }

    @Test
    fun onlyTheFirstBytesOfAFileAreRead() {
        val big = ByteArray(ModuleRiskScanner.MAX_ENTRY_BYTES + 100) { ' '.code.toByte() }
        "sketchy tweak".toByteArray().copyInto(big, ModuleRiskScanner.MAX_ENTRY_BYTES + 10)
        assertNull(scanner.scanZip(zip("module.prop" to "id=x".toByteArray(), "big.bin" to big)))
    }

    @Test
    fun parseSkipsUnknownLevelsAndEmptyEntries() {
        val risks = ModuleRisk.parseList(
            JSONArray(
                """[
                  {"level":"block","ids":["a"],"reason":{"vi":"v","en":"e"}},
                  {"level":"quarantine","ids":["b"],"reason":{"vi":"v","en":"e"}},
                  {"level":"warn","reason":{"vi":"v","en":"e"}},
                  {"level":"warn","patterns":["p q"]}
                ]"""
            )
        )
        assertEquals(listOf("a"), risks.single().ids)
        assertEquals("v", risks.single().reason(Locale.forLanguageTag("vi")))
        assertEquals("e", risks.single().reason(Locale.ENGLISH))
    }

    @Test
    fun rulesRoundTripKeepModuleRisks() {
        val rules = HidingRules(
            version = 9, updated = "", duckDetector = "", mountSources = setOf("KSU"),
            mapMarkers = emptyList(), suPaths = emptyList(), safeProps = emptyMap(),
            moduleRisks = listOf(block, warn),
        )
        assertEquals(rules.moduleRisks, HidingRules.parse(rules.toJson())?.moduleRisks)
        // rules from before version 9 have no list
        val old = HidingRules.parse(rules.toJson().replace("\"moduleRisks\"", "\"unused\""))
        assertTrue(old!!.moduleRisks.isEmpty())
    }
}
