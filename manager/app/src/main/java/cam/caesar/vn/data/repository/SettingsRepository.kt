package cam.su.kernel.data.repository

interface SettingsRepository {
    var checkUpdate: Boolean
    var betaUpdates: Boolean
    var checkModuleUpdate: Boolean
    var notifiedVersionCode: Long
    var lastSeenVersion: String?
    var askedNotificationPermission: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableSwipeDismiss: Boolean
    var pagerInterceptionMode: Int
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean

    /** Where the main navigation bar sits: NavBarPosition ordinal (auto, bottom, left, right). */
    var navBarPosition: Int
    var enableFloatingBottomBarBlur: Boolean
    var glassBackgroundType: Int
    var glassBackgroundBlur: Float
    var glassBackgroundDim: Float
    var glassImageVersion: Long
    var enableNavigationBadge: Boolean
    var navigationRailExpanded: Boolean
    var pageScale: Float
    var moduleDescriptionMaxLines: Int
    var enableWebDebugging: Boolean
    var moduleSortEnabledFirst: Boolean
    var moduleSortActionFirst: Boolean
    var moduleRepoSortOrder: Int
    var superuserShowSystemApps: Boolean
    var superuserShowOnlyPrimaryUserApps: Boolean
    var superuserSortOption: Int
    var suLogFilters: Set<String>?
    var autoJailbreak: Boolean
    var useSoftReboot: Boolean
    var roamingSlimes: Boolean
    var roamingSlimeCount: Int
    var slimeNightNap: Boolean
    /** the Inner Veterans intro over the home page while it starts up, on a launcher start */
    var startIntro: Boolean
    var conflictDetection: Boolean
    var conflictWarnOnFlash: Boolean
    var conflictIncludeProps: Boolean
    /** refuse modules the signed rules mark `block`; off: warn only */
    var moduleRiskBlock: Boolean
    /** check root hiding once after a boot that brings in a module */
    var moduleHidingScan: Boolean
    val intentToken: String

    suspend fun getSuCompatStatus(): String
    suspend fun getSuCompatPersistValue(): Long?
    fun isSuEnabled(): Boolean
    fun setSuEnabled(enabled: Boolean): Boolean
    fun setSuCompatModePref(mode: Int)
    fun getSuCompatModePref(): Int

    suspend fun getKernelUmountStatus(): String
    fun isKernelUmountEnabled(): Boolean
    fun setKernelUmountEnabled(enabled: Boolean): Boolean

    suspend fun getSelinuxHideStatus(): String
    fun isSelinuxHideEnabled(): Boolean
    fun setSelinuxHideEnabled(enabled: Boolean): Int

    suspend fun getSulogStatus(): String
    suspend fun getSulogPersistValue(): Long?
    suspend fun getSulogValue(): Long?
    fun setSulogEnabled(enabled: Boolean): Boolean

    suspend fun getAdbRootStatus(): String
    suspend fun getAdbRootPersistValue(): Long?
    suspend fun getAdbRootValue(): Long?
    fun setAdbRootEnabled(enabled: Boolean): Boolean
    suspend fun getShellSuAdbOnlyStatus(): String
    suspend fun getShellSuAdbOnlyPersistValue(): Long?
    suspend fun getShellSuAdbOnlyValue(): Long?
    fun setShellSuAdbOnlyEnabled(enabled: Boolean): Boolean

    fun isDefaultUmountModules(): Boolean
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    fun isLkmMode(): Boolean

    fun execCamdFeatureSave()
}
