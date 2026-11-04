package cam.su.kernel.data.repository

import cam.su.kernel.data.model.RepoModule

interface ModuleRepoRepository {
    suspend fun fetchModules(): Result<List<RepoModule>>
}
