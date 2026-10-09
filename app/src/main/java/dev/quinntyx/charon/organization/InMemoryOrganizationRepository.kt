package dev.quinntyx.charon.organization

import java.util.Locale
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
    newId: (() -> Long)? = null,
) : OrganizationRepository {
    private val mutex = Mutex()
    private val generateId = newId ?: incrementingIdGenerator(
        start = maxOf(
            initialFolders.maxOfOrNull { it.id.value } ?: 0,
            initialTags.maxOfOrNull { it.id.value } ?: 0,
        ) + 1,
    )
    private val mutableSnapshot = MutableStateFlow(
        OrganizationSnapshot(
            folders = initialFolders.toList(),
            tags = initialTags.toList(),
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
        require(initialFolders.haveDistinctFolderNamesPerCurrency()) {
            "Folder name and currency pairs must be unique"
        }
        require(initialTags.haveDistinctTagNames()) { "Tag names must be unique" }
    }

    override suspend fun createFolder(
        name: String,
        currencyCode: String,
    ): OrganizationResult<FolderId> = mutex.withLock {
        val normalizedName = when (val result = normalizeOrganizationName(name)) {
            is OrganizationResult.Success -> result.value
            is OrganizationResult.Failure -> return@withLock result
        }
        val normalizedCurrency = when (val result = normalizeCurrencyCode(currencyCode)) {
            is OrganizationResult.Success -> result.value
            is OrganizationResult.Failure -> return@withLock result
        }
        val current = mutableSnapshot.value
        if (current.folders.hasFolderName(normalizedName, normalizedCurrency)) {
            return@withLock duplicateName(normalizedName)
        }
        val id = FolderId(generateId())
        mutableSnapshot.value = current.copy(
            folders = current.folders + FolderAccount(
                id = id,
                name = normalizedName,
                currencyCode = normalizedCurrency,
            ),
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
        val existing = current.folders.firstOrNull { it.id == id } ?: return@withLock notFound()
        if (current.folders.hasFolderName(normalized, existing.currencyCode, excluding = id)) {
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
        val nonZeroBalances = if (folder.balanceMinorUnits == 0L) {
            emptyList()
        } else {
            listOf(folder.balance)
        }
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
        val id = TagId(generateId())
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

private fun incrementingIdGenerator(start: Long): () -> Long {
    var nextId = start
    return { nextId++ }
}

private fun List<FolderAccount>.hasFolderName(
    name: String,
    currencyCode: String,
    excluding: FolderId? = null,
): Boolean = any {
    it.id != excluding &&
        it.currencyCode == currencyCode &&
        it.name.equals(name, ignoreCase = true)
}

private fun List<TransactionTag>.hasTagName(name: String, excluding: TagId? = null): Boolean =
    any { it.id != excluding && it.name.equals(name, ignoreCase = true) }

private fun List<FolderAccount>.haveDistinctFolderNamesPerCurrency(): Boolean =
    map { it.name.lowercase(Locale.ROOT) to it.currencyCode }.distinct().size == size

private fun List<TransactionTag>.haveDistinctTagNames(): Boolean =
    map { it.name.lowercase(Locale.ROOT) }.distinct().size == size

private fun <T> duplicateName(name: String): OrganizationResult<T> =
    OrganizationResult.Failure(OrganizationFailure.NameAlreadyExists(name))

private fun <T> notFound(): OrganizationResult<T> =
    OrganizationResult.Failure(OrganizationFailure.NotFound)
