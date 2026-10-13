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

        sh("rm -rf $base $outside $fuse")
        println(if (failures == 0) "ALL PASS" else "$failures FAILED")
        System.exit(if (failures == 0) 0 else 1)
    }
}
