package cam.caesar.vn.data.repository

import cam.caesar.vn.data.model.Module
import cam.caesar.vn.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
