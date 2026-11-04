package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptRunnerTest {

    @Test
    fun runsLikeAModuleScriptInItsOwnSession() {
        val cmd = ScriptRunner.command("/data/adb/m/service.sh", "/data/adb/cam/bin/busybox")
        assertEquals(listOf("/system/bin/sh", "-c"), cmd.take(2))
        assertEquals(listOf("/data/adb/cam/bin/busybox", "sh", "/data/adb/m/service.sh"), cmd.drop(3))
        // without busybox the system shell runs it
        assertEquals(listOf("/system/bin/sh", "/x.sh"), ScriptRunner.command("/x.sh", null).drop(3))
        val env = ScriptRunner.environment("/system/bin")
        assertEquals("1", env["ASH_STANDALONE"])
        assertEquals("true", env["KSU"])
        assertEquals("/system/bin:/data/adb/cam/bin", env["PATH"])
        assertEquals("/system/bin:/system/xbin:/data/adb/cam/bin", ScriptRunner.environment(null)["PATH"])
    }

    @Test
    fun outputBehavesLikeATerminal() {
        val out = ScriptOutput()
        out.append("one\r\ntw")
        out.append("o\n\u001B[1;32mgreen\u001B[0m\n10%\r50%\r100%")
        assertEquals(listOf("one", "two", "green", "100%"), out.snapshot())
        out.append("\n")
        assertEquals(listOf("one", "two", "green", "100%"), out.snapshot())
        assertEquals("one\ntwo\ngreen\n100%", out.text())
    }

    @Test
    fun keepsOnlyTheLastLines() {
        val out = ScriptOutput(maxLines = 3)
        repeat(10) { out.append("$it\n") }
        assertEquals(listOf("8", "9"), out.snapshot().takeLast(2))
        assertEquals(true, out.snapshot().size <= 3)
        assertEquals(10, out.snapshot().size + out.dropped)
    }
}
