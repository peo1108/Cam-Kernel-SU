plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()
extra["kernelSourceId"] = getKernelSourceId()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always", "--match", "cam-v*"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

// Same value as KSU_SOURCE_ID in kernel/Kbuild: the git blob hash of the committed kernel/ and uapi/
// listing (kernel/tests/ left out). The Manager offers an LKM reinstall only when it differs.
fun getKernelSourceId(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "ls-tree", "--full-tree", "-r", "HEAD", "--", "kernel", "uapi"))
    val listing = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() != 0 || listing.isBlank()) return ""
    val content = listing.lineSequence()
        .filter { it.isNotEmpty() && !it.contains("kernel/tests/") }
        .joinToString("") { "$it\n" }
        .toByteArray()
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    digest.update("blob ${content.size}\u0000".toByteArray())
    digest.update(content)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    return 30000 + commitCount
}

// Cam tags only (cam-v3.0.1 -> 3.0.1); upstream v* tags would name builds after KernelSU.
fun getVersionName(): String {
    return getGitDescribe().removePrefix("cam-v")
}
