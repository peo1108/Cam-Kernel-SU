package cam.su.kernel.files

import java.io.File

/**
 * Debug builds only: runs the Files tab's root code on a real device, outside the app, so
 * FUSE, EXDEV, SELinux labels and setuid bits are the real ones.
 *
 *   su -c "CLASSPATH=$(pm path cam.su.kernel | cut -d: -f2) app_process /system/bin \
 *          cam.su.kernel.files.DeviceCheck /data/local/tmp/camfm_check /sdcard/camfm_check"
 *
 * Prints PASS/FAIL per check and exits 1 when one fails. Both folders are recreated.
 */
object DeviceCheck {
    private var failures = 0

    private fun check(name: String, block: () -> Boolean) {
        val ok = try {
            block()
        } catch (e: Throwable) {
            println("FAIL $name: $e")
            failures++
            return
        }
        println(if (ok) "PASS $name" else "FAIL $name")
        if (!ok) failures++
    }

    private fun sh(cmd: String): String =
        ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }

    private val quiet = object : OpListener {
        override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) =
            Resolution(ConflictChoice.OVERWRITE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.getOrElse(0) { "/data/local/tmp/camfm_check" }
        val fuse = args.getOrElse(1) { "/sdcard/camfm_check" }
        val outside = "${base}_outside"
        sh("rm -rf $base $outside $fuse; mkdir -p $base/tree/deep $outside $fuse")
        sh("echo precious > $outside/keep")
        sh("echo hello > $base/tree/a.txt; ln -s $outside $base/tree/deep/out; ln -s /nowhere $base/tree/broken")
        sh("echo '#!/system/bin/sh' > $base/tree/run.sh; chown 2000:2000 $base/tree/run.sh; chmod 4755 $base/tree/run.sh")
        sh("chcon u:object_r:system_file:s0 $base/tree/run.sh")
        sh("dd if=/dev/zero of=$base/big.bin bs=1048576 count=64 2>/dev/null")

        val fs = RootFs(OsBackend)
        println("uid=" + sh("id -u").trim() + " context=" + sh("cat /proc/self/attr/current").trim())

        check("listDir sees types, links, labels, owners") {
            val e = fs.listDir("$base/tree").associateBy { it.name }
            val run = e.getValue("run.sh")
            e.getValue("deep").isDir && e.getValue("broken").linkBroken && e.getValue("a.txt").type == FileEntry.TYPE_FILE &&
                run.owner == "shell" && run.context == "u:object_r:system_file:s0" &&
                FileFormat.permissions(run.mode) == "-rwsr-xr-x"
        }
        check("link to dir is a dir, link has no label") {
            val out = fs.listDir("$base/tree/deep").single()
            out.isLink && out.isDir && out.context == null && out.linkTarget == outside
        }
        check("stat of missing is null") { fs.stat("$base/nope") == null }

        check("atomic write keeps setuid, owner, label") {
            fs.writeAtomic("$base/tree/run.sh", "echo new\n".byteInputStream())
            val ls = sh("ls -lZ $base/tree/run.sh")
            File("$base/tree/run.sh").readText() == "echo new\n" &&
                ls.startsWith("-rwsr-xr-x") && " shell shell " in ls && "u:object_r:system_file:s0" in ls &&
                File("$base/tree").list()!!.none { it.endsWith(RootFs.TMP_SUFFIX) }
        }
        check("atomic write on FUSE storage") {
            fs.writeAtomic("$fuse/note.txt", "on sdcard".byteInputStream())
            fs.writeAtomic("$fuse/note.txt", "again".byteInputStream())
            File("$fuse/note.txt").readText() == "again"
        }
        check("rename never overwrites") {
            try {
                fs.rename("$base/tree/a.txt", "$base/tree/run.sh")
                false
            } catch (e: FsException) {
                e.errno == Errno.EEXIST
            }
        }
        check("recursive chmod skips links") {
            fs.chmod("$base/tree", 0x1C0, recursive = true)
            sh("stat -c %a $outside").trim() != "700" && sh("stat -c %a $base/tree/deep").trim() == "700"
        }

        check("copy tree keeps links, owner, label") {
            sh("mkdir -p $base/dst; chmod 4755 $base/tree/run.sh")
            FileOpEngine(OsBackend, quiet).run(FileOp(OpKind.COPY, listOf("$base/tree"), "$base/dst"))
            val ls = sh("ls -lZ $base/dst/tree/run.sh")
            val linkOk = sh("readlink $base/dst/tree/deep/out").trim() == outside
            linkOk && ls.startsWith("-rwsr-xr-x") && " shell shell " in ls && "u:object_r:system_file:s0" in ls
        }
        check("copy keeps exact mode bits") {
            sh("stat -c %a $base/tree/run.sh").trim() == sh("stat -c %a $base/dst/tree/run.sh").trim()
        }
        check("copy 64 MB") {
            val t0 = System.nanoTime()
            FileOpEngine(OsBackend, quiet).run(FileOp(OpKind.COPY, listOf("$base/big.bin"), fuse))
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("     64 MB to FUSE in $ms ms")
            File("$fuse/big.bin").length() == 64L * 1024 * 1024
        }
        check("move /data -> /sdcard copies, keeps the link FUSE refuses") {
            sh("mkdir -p $base/mv; echo x > $base/mv/f; ln -s f $base/mv/l")
            val result = FileOpEngine(OsBackend, quiet).run(FileOp(OpKind.MOVE, listOf("$base/mv"), fuse))
            result.skipped == 1 && File("$fuse/mv/f").readText() == "x\n" &&
                File("$base/mv").list()!!.toList() == listOf("l")
        }
        check("move inside /data renames") {
            sh("mkdir -p $base/mv2/sub; echo y > $base/mv2/sub/f; mkdir -p $base/dst2")
            val inode = sh("stat -c %i $base/mv2").trim()
            FileOpEngine(OsBackend, quiet).run(FileOp(OpKind.MOVE, listOf("$base/mv2"), "$base/dst2"))
            sh("stat -c %i $base/dst2/mv2").trim() == inode && !File("$base/mv2").exists()
        }
        check("cancel leaves no partial file") {
            var calls = 0
            try {
                FileOpEngine(OsBackend, quiet) { calls++ > 3 }.run(FileOp(OpKind.COPY, listOf("$base/big.bin"), "$base/dst"))
                false
            } catch (_: OpCancelledException) {
                File("$base/dst").list()!!.none { it == "big.bin" || it.endsWith(RootFs.TMP_SUFFIX) }
            }
        }
        check("fifos and devices are refused at once, never waited on") {
            sh("mkfifo $base/fifo")
            val t0 = System.nanoTime()
            val refused = listOf("$base/fifo", "/dev/zero").all { p ->
                try {
                    fs.openRead(p)
                    false
                } catch (e: FsException) {
                    e.errno == Errno.EINVAL
                }
            }
            // O_NONBLOCK: even opened directly, a fifo with no writer ends instead of hanging
            val ends = OsBackend.openRead("$base/fifo").use { it.read() } == -1
            refused && ends && System.nanoTime() - t0 < 2_000_000_000L
        }
        check("hex read at a big offset seeks") {
            val t0 = System.nanoTime()
            val bytes = fs.readRange("$base/big.bin", 60L * 1024 * 1024, 16)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("     16 bytes at 60 MB in $ms ms")
            bytes.size == 16 && ms < 200
        }
        check("zip edit streams a big stored entry and refuses a taken name") {
            val zip = "$base/edit.zip"
            val big = File("$base/big.bin")
            val crc = java.util.zip.CRC32()
            big.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
            }
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { out ->
                out.putNextEntry(
                    java.util.zip.ZipEntry("lib/big.so").apply {
                        method = java.util.zip.ZipEntry.STORED
                        size = big.length()
                        compressedSize = big.length()
                        this.crc = crc.value
                    },
                )
                big.inputStream().use { it.copyTo(out, 1 shl 20) }
                out.closeEntry()
                out.putNextEntry(java.util.zip.ZipEntry("a.txt"))
                out.write("a".toByteArray())
                out.closeEntry()
            }
            val t0 = System.nanoTime()
            ZipEditor(OsBackend).apply(zip, ZipEditor.Edits(renames = mapOf("a.txt" to "b.txt")))
            println("     64 MB zip rewritten in ${(System.nanoTime() - t0) / 1_000_000} ms")
            val names = java.util.zip.ZipFile(zip).use { z -> z.entries().asSequence().map { it.name to it.method }.toList() }
            val taken = try {
                ZipEditor(OsBackend).apply(zip, ZipEditor.Edits(renames = mapOf("b.txt" to "lib/big.so")))
                false
            } catch (e: FsException) {
                e.errno == Errno.EEXIST
            }
            names == listOf("lib/big.so" to java.util.zip.ZipEntry.STORED, "b.txt" to java.util.zip.ZipEntry.DEFLATED) && taken &&
                File(base).list()!!.none { it.endsWith(RootFs.TMP_SUFFIX) }
        }
        check("measuring / stops when cancelled") {
            val t0 = System.nanoTime()
            val deadline = t0 + 300_000_000L
            fs.measure("/") { System.nanoTime() > deadline }
            System.nanoTime() - t0 < 1_500_000_000L
        }

        check("delete tree does not follow links") {
            FileOpEngine(OsBackend, quiet).run(FileOp(OpKind.DELETE, listOf("$base/tree")))
            !File("$base/tree").exists() && File("$outside/keep").readText() == "precious\n"
        }
        check("top level folders are protected") {
            try {
                FileOpEngine(OsBackend, quiet).run(FileOp(OpKind.DELETE, listOf("/data")))
                false
            } catch (e: FsException) {
                e.errno == Errno.EPERM
            }
        }
        check("sdcard listing has entries") { fs.listDir("/sdcard").isNotEmpty() }
        check("/proc listing works") { fs.listDir("/proc").any { it.name == "self" && it.isLink } }
        check("highlighter regexes compile on ICU") {
            Highlighter.Lang.entries.all { lang -> Highlighter.spans("a=\"1\" # \${x} [s] <a b='c'/> {\"k\": 2}", lang).size >= 0 }
        }
        check("editor charsets exist on Android and round-trip") {
            val vi = "Ti\u1EBFng Vi\u1EC7t" // typed: one character per letter
            val saved = TextDoc.decode("a".toByteArray(), "windows-1258").encode(vi)
            val han = "\u4E2D\u6587"
            TextDoc.charsets() == TextDoc.CHARSETS &&
                java.text.Normalizer.normalize(TextDoc.decode(saved, "windows-1258").text, java.text.Normalizer.Form.NFC) == vi &&
                TextDoc.decode(han.toByteArray(charset("GBK")), "GBK").text == han
        }

        sh("rm -rf $base $outside $fuse")
        println(if (failures == 0) "ALL PASS" else "$failures FAILED")
        System.exit(if (failures == 0) 0 else 1)
    }
}
