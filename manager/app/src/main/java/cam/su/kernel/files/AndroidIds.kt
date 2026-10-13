package cam.su.kernel.files

/**
 * uid/gid to name the way bionic's getpwuid/getgrgid (and so `ls -l`) print them:
 * android_filesystem_config.h AIDs, u0_a123 for apps, u10_system for other users.
 */
object AndroidIds {
    private const val PER_USER_RANGE = 100_000
    private const val APP_START = 10_000
    private const val CACHE_GID_START = 20_000
    private const val EXT_GID_START = 30_000
    private const val EXT_CACHE_GID_START = 40_000
    private const val SHARED_GID_START = 50_000
    private const val ISOLATED_START = 90_000

    private val AIDS = mapOf(
        0 to "root", 1000 to "system", 1001 to "radio", 1002 to "bluetooth", 1003 to "graphics",
        1004 to "input", 1005 to "audio", 1006 to "camera", 1007 to "log", 1008 to "compass",
        1009 to "mount", 1010 to "wifi", 1011 to "adb", 1012 to "install", 1013 to "media",
        1014 to "dhcp", 1015 to "sdcard_rw", 1016 to "vpn", 1017 to "keystore", 1018 to "usb",
        1019 to "drm", 1020 to "mdnsr", 1021 to "gps", 1023 to "media_rw", 1024 to "mtp",
        1026 to "drmrpc", 1027 to "nfc", 1028 to "sdcard_r", 1029 to "clat", 1030 to "loop_radio",
        1031 to "mediadrm", 1032 to "package_info", 1033 to "sdcard_pics", 1034 to "sdcard_av",
        1035 to "sdcard_all", 1036 to "logd", 1037 to "shared_relro", 1038 to "dbus",
        1039 to "tlsdate", 1040 to "mediaex", 1041 to "audioserver", 1042 to "metrics_coll",
        1043 to "metricsd", 1044 to "webserv", 1045 to "debuggerd", 1046 to "mediacodec",
        1047 to "cameraserver", 1048 to "firewall", 1049 to "trunks", 1050 to "nvram",
        1051 to "dns", 1052 to "dns_tether", 1053 to "webview_zygote", 1054 to "vehicle_network",
        1055 to "media_audio", 1056 to "media_video", 1057 to "media_image", 1058 to "tombstoned",
        1059 to "media_obb", 1060 to "ese", 1061 to "ota_update", 1062 to "automotive_evs",
        1063 to "lowpan", 1064 to "hsm", 1065 to "reserved_disk", 1066 to "statsd",
        1067 to "incidentd", 1068 to "secure_element", 1069 to "lmkd", 1070 to "llkd",
        1071 to "iorapd", 1072 to "gpu_service", 1073 to "network_stack", 1074 to "gsid",
        1075 to "fsverity_cert", 1076 to "credstore", 1077 to "external_storage",
        1078 to "ext_data_rw", 1079 to "ext_obb_rw", 1080 to "context_hub",
        1081 to "virtualizationservice", 1082 to "artd", 1083 to "uwb", 1084 to "thread_network",
        1085 to "diced", 1086 to "dmesgd", 1087 to "jc_weaver", 1088 to "jc_strongbox",
        1089 to "jc_identitycred", 1090 to "sdk_sandbox", 1091 to "security_log_writer",
        1092 to "prng_seeder",
        2000 to "shell", 2001 to "cache", 2002 to "diag",
        3001 to "net_bt_admin", 3002 to "net_bt", 3003 to "inet", 3004 to "net_raw",
        3005 to "net_admin", 3006 to "net_bw_stats", 3007 to "net_bw_acct", 3009 to "readproc",
        3010 to "wakelock", 3011 to "uhid", 3012 to "readtracefs",
        9997 to "everybody", 9998 to "misc", 9999 to "nobody",
    )

    fun name(id: Int): String {
        if (id < 0) return id.toUInt().toString()
        val user = id / PER_USER_RANGE
        val appId = id % PER_USER_RANGE
        AIDS[appId]?.let { return if (user == 0) it else "u${user}_$it" }
        return when (appId) {
            in APP_START until CACHE_GID_START -> "u${user}_a${appId - APP_START}"
            in CACHE_GID_START until EXT_GID_START -> "u${user}_a${appId - CACHE_GID_START}_cache"
            in EXT_GID_START until EXT_CACHE_GID_START -> "u${user}_a${appId - EXT_GID_START}_ext"
            in EXT_CACHE_GID_START until SHARED_GID_START ->
                "u${user}_a${appId - EXT_CACHE_GID_START}_ext_cache"
            in SHARED_GID_START until SHARED_GID_START + 10_000 -> "all_a${appId - SHARED_GID_START}"
            in ISOLATED_START until PER_USER_RANGE -> "u${user}_i${appId - ISOLATED_START}"
            else -> id.toString()
        }
    }
}
