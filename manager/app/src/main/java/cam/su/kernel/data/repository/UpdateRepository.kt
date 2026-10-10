package cam.su.kernel.data.repository

import cam.su.kernel.update.UpdateInfo

interface UpdateRepository {
    /** The newest Cam release newer than the running Manager, null when there is none. */
    suspend fun fetchLatest(): Result<UpdateInfo?>
}
