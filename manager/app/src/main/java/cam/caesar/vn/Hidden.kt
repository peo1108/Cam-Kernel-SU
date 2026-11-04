package cam.caesar.vn

/**
 * Upstream (KernelSU) names that must keep their value at runtime: the module APIs (`KSU=true`
 * for scripts, the `ksu` WebUI bridge, `ksu://icon`, the mui.kernelsu.org WebUI host), the
 * pre-rename SELinux domain, the online template index and the credit links. Any app that can see
 * the Manager can read its APK, and a root checker scanning it must find no KernelSU name, so they
 * are stored with every character shifted up by one and decoded when used
 * (docs/CAM_CHANGES.md row 85). Make new ones with `scripts/check_apk_strings.py --encode <text>`,
 * and check a release APK with `scripts/check_apk_strings.py <apk>`.
 */
object Hidden {
    fun s(shifted: String): String = buildString(shifted.length) {
        for (c in shifted) append(c - 1)
    }

    /** `KSU`: the variable module scripts check (`KSU=true`), next to our `CAM`. */
    val LEGACY_ENV: String get() = s("LTV")

    /** `KSU`: the mount source module mounts carry (left out of hiding-rules.json). */
    val MOUNT_SOURCE: String get() = s("LTV")

    /** `ksu`: the WebUI JavaScript bridge and icon URL scheme modules use. */
    val MODULE_API: String get() = s("ltv")

    /** `mui.kernelsu.org`: the virtual host module WebUIs are served from. */
    val WEBUI_HOST: String get() = s("nvj/lfsofmtv/psh")

    /** `u:r:ksu:s0`: the domain stored profiles keep so older kernels read them. */
    val LEGACY_DOMAIN: String get() = s("v;s;ltv;t1")

    /** `https://kernelsu.org/templates/`: the online App Profile templates. */
    val TEMPLATE_BASE: String get() = s("iuuqt;00lfsofmtv/psh0ufnqmbuft0")

    /** `https://github.com/tiann/KernelSU`: credit link on the About page. */
    val UPSTREAM_GITHUB: String get() = s("iuuqt;00hjuivc/dpn0ujboo0LfsofmTV")

    /** `https://t.me/KernelSU`: credit link on the About page. */
    val UPSTREAM_TELEGRAM: String get() = s("iuuqt;00u/nf0LfsofmTV")
}
