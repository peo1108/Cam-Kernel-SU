package cam.caesar.vn.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellQuoteTest {
    @Test
    fun aQuoteIsClosedEscapedAndReopened() {
        assertEquals("'plain'", shellQuote("plain"))
        assertEquals("''", shellQuote(""))
        // what reached the root shell as `x';>/data/local/tmp/f;'` before the rules were quoted
        assertEquals("'x'\\'';>/data/local/tmp/f;'\\'''", shellQuote("x';>/data/local/tmp/f;'"))
        // the hand-made copy in BootGuardCli wrote ''' and left the quote open
        assertEquals("'a'\\''b'", shellQuote("a'b"))
        assertTrue(shellQuote("\$(id) `id` \"x\"").let { it.startsWith("'") && it.endsWith("'") && it.count { c -> c == '\'' } == 2 })
    }

    @Test
    fun terminalRcQuotesTheFolderAndScript() {
        val rc = TerminalSetup.rc("/sdcard/it's", "/data/a b.sh", busybox = false)
        assertTrue(rc, rc.startsWith("cd '/sdcard/it'\\''s' 2>/dev/null\n"))
        assertTrue(rc, "KSU=true CAM=true sh '/data/a b.sh'\n" in rc)
    }
}
