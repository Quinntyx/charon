package dev.quinntyx.charon.organization

import kotlinx.coroutines.flow.StateFlow

/**
 * Boundary owned by the organization feature. A Room-backed implementation can combine account/tag
 * rows with transaction and recurrence aggregates without making the UI depend on database types.
 */
interface OrganizationRepository {
    val snapshot: StateFlow<OrganizationSnapshot>

    suspend fun createFolder(name: String, currencyCode: String): OrganizationResult<FolderId>
    suspend fun renameFolder(id: FolderId, name: String): OrganizationResult<Unit>
    suspend fun setFolderArchived(id: FolderId, archived: Boolean): OrganizationResult<Unit>
    suspend fun deleteFolder(id: FolderId): OrganizationResult<Unit>

    suspend fun createTag(name: String): OrganizationResult<TagId>
    suspend fun renameTag(id: TagId, name: String): OrganizationResult<Unit>
    suspend fun setTagArchived(id: TagId, archived: Boolean): OrganizationResult<Unit>
    suspend fun deleteTag(id: TagId): OrganizationResult<Unit>
}
