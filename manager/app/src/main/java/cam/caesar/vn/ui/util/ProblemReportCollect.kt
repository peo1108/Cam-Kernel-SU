package cam.caesar.vn.ui.util

import android.content.Context
import android.os.Build
import android.system.Os
import cam.caesar.vn.Cam
import cam.caesar.vn.account.Account
import cam.caesar.vn.account.ProblemReport
import cam.caesar.vn.data.repository.HidingRulesRepository
import cam.caesar.vn.files.shellQuote
import cam.caesar.vn.ui.screen.home.getManagerVersion

// Cam-only (docs/CAM_CHANGES.md row 98): what a problem report for Cam holds, gathered with root.
// Unlike upstream's bugreport (LogEvent.kt: whole logcat, kallsyms, the app list, the allowlist)
// it keeps to what helps with Cam itself, small enough to send over a phone connection.

/** Whose properties go in: build, boot state, USB/adb and what the Y700 fixes touch. No serial number. */
private const val PROPS =
    "^\\[(ro\\.(build|product|boot|vendor\\.build|system\\.build|debuggable|secure|crypto|zygote)|" +
        "persist\\.(sys\\.usb|logd|adb)|sys\\.(boot_completed|usb)|init\\.svc\\.(adbd|zygote)|service\\.adb)"

/** Where camd keeps the boot logcat and dmesg it saves (defs::LOG_DIR), with their previous boot's. */
private const val BOOT_LOGS = "/data/adb/cam/log"

/**
 * Gathers a problem report on this device. Slow (the hiding check alone takes seconds): call it
 * off the main thread. [note] is what the customer wrote.
 */
fun collectProblemReport(context: Context, note: String): ProblemReport {
    val camd = getCamDaemonPath()
    val shell = getRootShell()

    // stdout, then stderr when there is some; a command never fails the report
    fun sh(cmd: String): String {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        runCatching { shell.newJob().add(cmd).to(out, err).exec() }.onFailure { return "[failed: ${it.message}]" }
        return buildString {
            append(out.joinToString("\n"))
            if (err.isNotEmpty()) append("\n[stderr]\n").append(err.joinToString("\n"))
        }.trim()
    }

    fun camdCmd(args: String, seconds: Int = 30) = sh("timeout $seconds $camd $args")

    // The logs first: they should show what led up to the report, not the commands gathering it.
    val logcat = sh("logcat -b main,system -d -v threadtime -t 4000")
    val logcatCrash = sh("logcat -b crash -d -t 500")
    val dmesg = sh("dmesg | tail -n 2000")

    val version = runCatching { getManagerVersion(context) }.getOrNull()
    val rulesRepo = HidingRulesRepository(context)
    val rules = runCatching { rulesRepo.current() }.getOrNull()
    val uname = runCatching { Os.uname() }.getOrNull()
    val camdVersion = camdCmd("--version", 10).lineSequence().firstOrNull().orEmpty()

    val basic = buildString {
        appendLine("Manager: ${version?.versionName} (${version?.versionCode})")
        appendLine("camd: $camdVersion")
        appendLine("camd source: ${camdCmd("debug source-id", 10)}")
        appendLine("Kernel (Cam): ${Cam.version}, LKM: ${Cam.isLkmMode}, safe mode: ${Cam.isSafeMode}")
        appendLine("Hiding rules: v${rules?.version}")
        appendLine("Brand: ${Build.BRAND}, model: ${Build.MODEL}, device: ${Build.DEVICE}, product: ${Build.PRODUCT}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), fingerprint: ${Build.FINGERPRINT}")
        appendLine("Kernel: ${uname?.release} ${uname?.version} ${uname?.machine}")
        appendLine("SELinux: ${sh("getenforce")}")
        appendLine("Slot: ${sh("getprop ro.boot.slot_suffix")}")
        appendLine("Uptime: ${sh("uptime")}")
        appendLine("Account: ${Account.email() ?: "not linked"}")
    }

    val rulesFile = runCatching { rulesRepo.currentFile() }.getOrNull()
    val audit = camdCmd("hiding-audit" + (rulesFile?.let { " --rules ${shellQuote(it.absolutePath)}" } ?: ""), 90)

    val camLog = buildString {
        appendLine("== ls -la $BOOT_LOGS")
        appendLine(sh("ls -la $BOOT_LOGS"))
        for (name in listOf("logcat.log", "logcat.old.log")) {
            appendLine("== camd lines in $name")
            appendLine(sh("grep -E ' [VDIWEF] (Cam|camd) *: ' $BOOT_LOGS/$name | tail -n 400"))
        }
        for (name in listOf("dmesg.log", "dmesg.old.log")) {
            appendLine("== KernelSU lines in $name")
            appendLine(sh("grep -iE 'kernelsu|ksu|camsu|selinux_hide' $BOOT_LOGS/$name | tail -n 300"))
        }
    }

    val pstore = sh(
        "ls -la /sys/fs/pstore; for f in /sys/fs/pstore/console-ramoops* /sys/fs/pstore/dmesg-ramoops*; do " +
            "[ -f \"\$f\" ] && { echo \"== \$f\"; tail -c 131072 \"\$f\"; }; done",
    )

    val sections = listOf(
        "basic" to basic,
        "hiding_audit" to audit,
        "features" to camdCmd("feature list"),
        "boot_guard" to camdCmd("boot-guard status"),
        "hide_bootloader" to camdCmd("hide-bootloader status"),
        "modules" to runCatching { listModules() }.getOrElse { "[failed: ${it.message}]" },
        "props" to sh("getprop | grep -E '$PROPS' | grep -v serialno"),
        "cam_log" to camLog,
        "pstore" to pstore,
        "dmesg" to dmesg,
        "logcat_crash" to logcatCrash,
        "logcat" to logcat,
        "mounts" to sh("cat /proc/1/mountinfo"),
    )

    val meta = mapOf(
        "model" to Account.deviceName(),
        "android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "cam" to "${version?.versionName} (${version?.versionCode})",
        "kernel" to (uname?.release ?: ""),
        "lkm" to Cam.version.toString(),
        "camd" to camdVersion,
        "rules" to (rules?.let { "v${it.version}" } ?: ""),
    )
    return ProblemReport.build(meta, note, sections)
}
