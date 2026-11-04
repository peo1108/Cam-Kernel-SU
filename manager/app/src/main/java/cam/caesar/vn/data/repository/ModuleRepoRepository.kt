package cam.caesar.vn.data.repository

import cam.caesar.vn.data.model.RepoModule

interface ModuleRepoRepository {
    suspend fun fetchModules(): Result<List<RepoModule>>
}
