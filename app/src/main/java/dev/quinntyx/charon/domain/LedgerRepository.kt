package dev.quinntyx.charon.domain

import kotlinx.coroutines.flow.Flow

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class Blocked(val reason: String) : DeleteResult
}

interface LedgerRepository {
    suspend fun createAccount(name: String, currencyCode: String): Account
    suspend fun updateAccount(id: Long, name: String, archived: Boolean): Account
    suspend fun renameAccount(id: Long, name: String): Account
    suspend fun setAccountArchived(id: Long, archived: Boolean): Account
    suspend fun deleteAccountSafely(id: Long): DeleteResult
    suspend fun getAccountBalance(id: Long): Long
    suspend fun getAccounts(includeArchived: Boolean = false): List<Account>
    fun observeAccounts(): Flow<List<Account>>

    suspend fun getOrCreateMerchant(name: String): Merchant
    suspend fun renameMerchant(id: Long, name: String): Merchant
    suspend fun getMerchants(): List<Merchant>

    suspend fun getOrCreateTag(name: String): Tag
    suspend fun createTag(name: String): Tag
    suspend fun updateTag(id: Long, name: String, archived: Boolean): Tag
    suspend fun renameTag(id: Long, name: String): Tag
    suspend fun setTagArchived(id: Long, archived: Boolean): Tag
    suspend fun deleteTagSafely(id: Long): DeleteResult
    suspend fun getTags(includeArchived: Boolean = false): List<Tag>
    fun observeTags(): Flow<List<Tag>>

    suspend fun addReceiptReference(
        localUri: String,
        displayName: String? = null,
        mimeType: String? = null,
        capturedAtEpochMillis: Long,
        sha256: String? = null,
    ): ReceiptReference

    suspend fun record(transaction: NewTransaction): LedgerTransaction
    suspend fun updateTransaction(id: Long, transaction: NewTransaction): LedgerTransaction
    suspend fun getTransaction(id: Long): LedgerTransaction?
    suspend fun getTransactions(): List<LedgerTransaction>
    fun observeTransactions(): Flow<List<LedgerTransaction>>

    /** Expense-only totals. Income and transfers are deliberately excluded. */
    suspend fun getExpenseTotals(
        fromEpochMillisInclusive: Long,
        toEpochMillisExclusive: Long,
    ): List<CurrencyTotal>
}
