package cam.su.kernel.data.repository

import cam.su.kernel.data.model.Module
import cam.su.kernel.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
