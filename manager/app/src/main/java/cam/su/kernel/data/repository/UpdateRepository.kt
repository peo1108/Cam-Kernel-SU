package cam.su.kernel.data.repository

import cam.su.kernel.account.LockedRelease
import cam.su.kernel.update.UpdateInfo

interface UpdateRepository {
    /** The newest Cam release newer than the running Manager, null when there is none. */
    suspend fun fetchLatest(): Result<UpdateInfo?>

    /**
     * The newest release this account cannot install yet (20-day wait) and that is newer than the
     * running Manager and than [openVersionCode], or null. The app offers early access for it.
     */
    suspend fun fetchOffer(openVersionCode: Long = 0): Result<LockedRelease?>
}
