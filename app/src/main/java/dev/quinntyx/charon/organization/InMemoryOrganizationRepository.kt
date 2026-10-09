package dev.quinntyx.charon.organization

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local implementation used by the standalone feature screen and unit tests. It deliberately starts
 * empty; production integration should provide a Room-backed [OrganizationRepository].
 */
class InMemoryOrganizationRepository(
    initialFolders: List<FolderAccount> = emptyList(),
    initialTags: List<TransactionTag> = emptyList(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : OrganizationRepository {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(
        OrganizationSnapshot(
            folders = initialFolders.map { it.normalizedCopy() },
            tags = initialTags.map { it.normalizedCopy() },
        ),
    )

    override val snapshot: StateFlow<OrganizationSnapshot> = mutableSnapshot.asStateFlow()

    init {
        require(initialFolders.map { it.id }.distinct().size == initialFolders.size) {
            "Folder ids must be unique"
        }
        require(initialTags.map { it.id }.distinct().size == initialTags.size) {
            "Tag ids must be unique"
        }
        require(initialFolders.haveDistinctFolderNames()) { "Folder names must be unique" }
        require(initialTags.haveDistinctTagNames()) { "Tag names must be unique" }
    }

    override suspend fun createFolder(name: String): OrganizationResult<FolderId> = mutex.withLock {
        val normalized = when (val result = normalizeOrganizationName(name)) {
            is OrganizationResult.Success -> result.value
            is OrganizationResult.Failure -> return@withLock result
        }
        val current = mutableSnapshot.value
        if (current.folders.hasFolderName(normalized)) {
            return@withLock duplicateName(normalized)
        }
        val id = FolderId(newId())
        mutableSnapshot.value = current.copy(
            folders = current.folders + FolderAccount(id = id, name = normalized),
        )
        OrganizationResult.Success(id)
    }

    override suspend fun renameFolder(
        id: FolderId,
        name: String,
    ): OrganizationResult<Unit> = mutex.withLock {
        val normalized = when (val result = normalizeOrganizationName(name)) {
            is OrganizationResult.Success -> result.value
            is OrganizationResult.Failure -> return@withLock result
        }
        val current = mutableSnapshot.value
        if (current.folders.none { it.id == id }) return@withLock notFound()
        if (current.folders.hasFolderName(normalized, excluding = id)) {
            return@withLock duplicateName(normalized)
        }
        mutableSnapshot.value = current.copy(
            folders = current.folders.map { folder ->
                if (folder.id == id) folder.copy(name = normalized) else folder
            },
        )
        OrganizationResult.Success(Unit)
    }

    override suspend fun setFolderArchived(
        id: FolderId,
        archived: Boolean,
    ): OrganizationResult<Unit> = mutex.withLock {
        val current = mutableSnapshot.value
        if (current.folders.none { it.id == id }) return@withLock notFound()
        mutableSnapshot.value = current.copy(
            folders = current.folders.map { folder ->
                if (folder.id == id) folder.copy(isArchived = archived) else folder
            },
        )
        OrganizationResult.Success(Unit)
    }

    override suspend fun deleteFolder(id: FolderId): OrganizationResult<Unit> = mutex.withLock {
        val current = mutableSnapshot.value
        val folder = current.folders.firstOrNull { it.id == id } ?: return@withLock notFound()
        val nonZeroBalances = folder.balances.filter { it.minorUnits != 0L }
        if (
            folder.transactionCount > 0 ||
            folder.recurringRuleCount > 0 ||
            nonZeroBalances.isNotEmpty()
        ) {
            return@withLock OrganizationResult.Failure(
                OrganizationFailure.DeletionBlocked(
                    transactionCount = folder.transactionCount,
                    recurringRuleCount = folder.recurringRuleCount,
                    nonZeroBalances = nonZeroBalances,
                ),
            )
        }
        mutableSnapshot.value = current.copy(folders = current.folders.filterNot { it.id == id })
        OrganizationResult.Success(Unit)
    }

    override suspend fun createTag(name: String): OrganizationResult<TagId> = mutex.withLock {
        val normalized = when (val result = normalizeOrganizationName(name)) {
            is OrganizationResult.Success -> result.value
            is OrganizationResult.Failure -> return@withLock result
        }
        val current = mutableSnapshot.value
        if (current.tags.hasTagName(normalized)) return@withLock duplicateName(normalized)
        val id = TagId(newId())
        mutableSnapshot.value = current.copy(
            tags = current.tags + TransactionTag(id = id, name = normalized),
        )
        OrganizationResult.Success(id)
    }

    override suspend fun renameTag(id: TagId, name: String): OrganizationResult<Unit> =
        mutex.withLock {
            val normalized = when (val result = normalizeOrganizationName(name)) {
                is OrganizationResult.Success -> result.value
                is OrganizationResult.Failure -> return@withLock result
            }
            val current = mutableSnapshot.value
            if (current.tags.none { it.id == id }) return@withLock notFound()
            if (current.tags.hasTagName(normalized, excluding = id)) {
                return@withLock duplicateName(normalized)
            }
            mutableSnapshot.value = current.copy(
                tags = current.tags.map { tag ->
                    if (tag.id == id) tag.copy(name = normalized) else tag
                },
            )
            OrganizationResult.Success(Unit)
        }

    override suspend fun setTagArchived(
        id: TagId,
        archived: Boolean,
    ): OrganizationResult<Unit> = mutex.withLock {
        val current = mutableSnapshot.value
        if (current.tags.none { it.id == id }) return@withLock notFound()
        mutableSnapshot.value = current.copy(
            tags = current.tags.map { tag ->
                if (tag.id == id) tag.copy(isArchived = archived) else tag
            },
        )
        OrganizationResult.Success(Unit)
    }

    override suspend fun deleteTag(id: TagId): OrganizationResult<Unit> = mutex.withLock {
        val current = mutableSnapshot.value
        val tag = current.tags.firstOrNull { it.id == id } ?: return@withLock notFound()
        if (tag.transactionCount > 0 || tag.recurringRuleCount > 0) {
            return@withLock OrganizationResult.Failure(
                OrganizationFailure.DeletionBlocked(
                    transactionCount = tag.transactionCount,
                    recurringRuleCount = tag.recurringRuleCount,
                ),
            )
        }
        mutableSnapshot.value = current.copy(tags = current.tags.filterNot { it.id == id })
        OrganizationResult.Success(Unit)
    }
}

private fun FolderAccount.normalizedCopy(): FolderAccount = copy(
    balances = balances.sortedBy { it.currencyCode },
)

private fun TransactionTag.normalizedCopy(): TransactionTag = copy()

private fun List<FolderAccount>.hasFolderName(name: String, excluding: FolderId? = null): Boolean =
    any { it.id != excluding && it.name.equals(name, ignoreCase = true) }

private fun List<TransactionTag>.hasTagName(name: String, excluding: TagId? = null): Boolean =
    any { it.id != excluding && it.name.equals(name, ignoreCase = true) }

private fun List<FolderAccount>.haveDistinctFolderNames(): Boolean =
    map { it.name.lowercase(Locale.ROOT) }.distinct().size == size

private fun List<TransactionTag>.haveDistinctTagNames(): Boolean =
    map { it.name.lowercase(Locale.ROOT) }.distinct().size == size

private fun <T> duplicateName(name: String): OrganizationResult<T> =
    OrganizationResult.Failure(OrganizationFailure.NameAlreadyExists(name))

private fun <T> notFound(): OrganizationResult<T> =
    OrganizationResult.Failure(OrganizationFailure.NotFound)
