package dev.quinntyx.charon.organization

import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InMemoryOrganizationRepositoryTest {
    @Test
    fun createNormalizesNamesAndKeepsAccountCurrenciesSeparate() = runTest {
        val ids = ArrayDeque(listOf(1L, 2L, 3L))
        val repository = InMemoryOrganizationRepository(newId = { ids.removeFirst() })

        assertSuccess(repository.createFolder("  Daily   checking ", " usd "))
        val duplicate = repository.createFolder("daily CHECKING", "USD")
        assertFailure<OrganizationFailure.NameAlreadyExists>(duplicate)

        // The same display name in another currency is a separate account, never a mixed total.
        assertSuccess(repository.createFolder("Daily checking", "EUR"))
        // Folder and tag names intentionally have separate namespaces and meanings.
        assertSuccess(repository.createTag("Daily checking"))

        assertEquals(listOf("EUR", "USD"), repository.snapshot.value.activeFolders.map { it.currencyCode })
        assertEquals("Daily checking", repository.snapshot.value.activeTags.single().name)
    }

    @Test
    fun archiveRemovesItemsFromActiveSelectorListsAndRestoreReturnsThem() = runTest {
        val folder = FolderAccount(FolderId(1), "Cash", "USD")
        val tag = TransactionTag(TagId(1), "Food")
        val repository = InMemoryOrganizationRepository(listOf(folder), listOf(tag))

        assertSuccess(repository.setFolderArchived(folder.id, true))
        assertSuccess(repository.setTagArchived(tag.id, true))
        assertTrue(repository.snapshot.value.activeFolders.isEmpty())
        assertTrue(repository.snapshot.value.activeTags.isEmpty())
        assertEquals(listOf(folder.id), repository.snapshot.value.archivedFolders.map { it.id })
        assertEquals(listOf(tag.id), repository.snapshot.value.archivedTags.map { it.id })

        assertSuccess(repository.setFolderArchived(folder.id, false))
        assertSuccess(repository.setTagArchived(tag.id, false))
        assertEquals(listOf(folder.id), repository.snapshot.value.activeFolders.map { it.id })
        assertEquals(listOf(tag.id), repository.snapshot.value.activeTags.map { it.id })
    }

    @Test
    fun folderDeletionIsBlockedByReferencesOrNonZeroBalance() = runTest {
        val folder = FolderAccount(
            id = FolderId(1),
            name = "Checking",
            currencyCode = "USD",
            balanceMinorUnits = 12_345,
            transactionCount = 4,
            recurringRuleCount = 1,
        )
        val repository = InMemoryOrganizationRepository(initialFolders = listOf(folder))

        val failure = assertFailure<OrganizationFailure.DeletionBlocked>(
            repository.deleteFolder(folder.id),
        )
        assertEquals(4, failure.transactionCount)
        assertEquals(1, failure.recurringRuleCount)
        assertEquals(listOf(CurrencyBalance("USD", 12_345)), failure.nonZeroBalances)
        assertEquals(folder, repository.snapshot.value.folders.single())
    }

    @Test
    fun tagDeletionIsBlockedByHistoricalOrRecurringReferences() = runTest {
        val tag = TransactionTag(
            id = TagId(1),
            name = "Rent",
            transactionCount = 12,
            recurringRuleCount = 1,
        )
        val repository = InMemoryOrganizationRepository(initialTags = listOf(tag))

        val failure = assertFailure<OrganizationFailure.DeletionBlocked>(
            repository.deleteTag(tag.id),
        )

        assertEquals(12, failure.transactionCount)
        assertEquals(1, failure.recurringRuleCount)
        assertEquals(listOf(tag), repository.snapshot.value.tags)
    }

    @Test
    fun unusedItemsCanBePermanentlyDeletedEvenWhenArchived() = runTest {
        val folder = FolderAccount(
            id = FolderId(1),
            name = "Old account",
            currencyCode = "USD",
            isArchived = true,
            balanceMinorUnits = 0,
        )
        val tag = TransactionTag(TagId(1), "Old tag", isArchived = true)
        val repository = InMemoryOrganizationRepository(listOf(folder), listOf(tag))

        assertSuccess(repository.deleteFolder(folder.id))
        assertSuccess(repository.deleteTag(tag.id))

        assertTrue(repository.snapshot.value.folders.isEmpty())
        assertTrue(repository.snapshot.value.tags.isEmpty())
    }

    @Test
    fun renamePreservesIdentityCurrencyUsageAndBalance() = runTest {
        val folder = FolderAccount(
            id = FolderId(1),
            name = "Wallet",
            currencyCode = "JPY",
            balanceMinorUnits = -250,
            transactionCount = 3,
        )
        val repository = InMemoryOrganizationRepository(initialFolders = listOf(folder))

        assertSuccess(repository.renameFolder(folder.id, "  Travel wallet "))

        assertEquals(folder.copy(name = "Travel wallet"), repository.snapshot.value.folders.single())
    }

    @Test
    fun invalidInputsAndMissingIdsDoNotMutateState() = runTest {
        val repository = InMemoryOrganizationRepository()

        assertFailure<OrganizationFailure.InvalidName>(repository.createFolder("   \n ", "USD"))
        assertFailure<OrganizationFailure.InvalidName>(repository.createTag("x".repeat(121)))
        assertFailure<OrganizationFailure.InvalidCurrency>(
            repository.createFolder("Cash", "ZZZ"),
        )
        assertFailure<OrganizationFailure.NotFound>(
            repository.renameFolder(FolderId(999), "Valid"),
        )
        assertTrue(repository.snapshot.value.folders.isEmpty())
        assertTrue(repository.snapshot.value.tags.isEmpty())
    }

    @Test
    fun activeAndArchivedListsAreSortedByNameThenCurrency() {
        val snapshot = OrganizationSnapshot(
            folders = listOf(
                FolderAccount(FolderId(1), "zebra", "USD"),
                FolderAccount(FolderId(2), "Alpha", "USD"),
                FolderAccount(FolderId(3), "Alpha", "EUR"),
                FolderAccount(FolderId(4), "middle", "USD", isArchived = true),
            ),
            tags = listOf(
                TransactionTag(TagId(1), "Bills"),
                TransactionTag(TagId(2), "auto"),
                TransactionTag(TagId(3), "Archived", isArchived = true),
            ),
        )

        assertEquals(
            listOf("Alpha EUR", "Alpha USD", "zebra USD"),
            snapshot.activeFolders.map { "${it.name} ${it.currencyCode}" },
        )
        assertEquals(listOf("middle"), snapshot.archivedFolders.map { it.name })
        assertEquals(listOf("auto", "Bills"), snapshot.activeTags.map { it.name })
        assertEquals(listOf("Archived"), snapshot.archivedTags.map { it.name })
    }

    @Test
    fun minorUnitFormattingNeverUsesFloatingPointAndHonorsCurrencyScale() {
        assertEquals("USD 12.34", CurrencyBalance("USD", 1_234).displayText(Locale.US))
        assertEquals("USD -0.05", CurrencyBalance("USD", -5).displayText(Locale.US))
        assertEquals("JPY 123", CurrencyBalance("JPY", 123).displayText(Locale.US))
        assertEquals(
            "USD -92233720368547758.08",
            CurrencyBalance("USD", Long.MIN_VALUE).displayText(Locale.US),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun folderRejectsNonUppercaseCurrencyCode() {
        FolderAccount(FolderId(1), "Bad data", "usd")
    }

    private fun assertSuccess(result: OrganizationResult<*>) {
        assertTrue("Expected success but was $result", result is OrganizationResult.Success)
    }

    private inline fun <reified T : OrganizationFailure> assertFailure(
        result: OrganizationResult<*>,
    ): T {
        if (result !is OrganizationResult.Failure) fail("Expected failure but was $result")
        val reason = (result as OrganizationResult.Failure).reason
        if (reason !is T) fail("Expected ${T::class.java.simpleName} but was $reason")
        return reason as T
    }
}
