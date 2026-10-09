package dev.quinntyx.charon.domain

interface LedgerRepository {
    suspend fun createAccount(name: String, currencyCode: String): Account
    suspend fun updateAccount(id: Long, name: String, archived: Boolean): Account
    suspend fun getAccounts(includeArchived: Boolean = false): List<Account>

    suspend fun getOrCreateMerchant(name: String): Merchant
    suspend fun renameMerchant(id: Long, name: String): Merchant
    suspend fun getMerchants(): List<Merchant>

    suspend fun getOrCreateTag(name: String): Tag
    suspend fun updateTag(id: Long, name: String, archived: Boolean): Tag
    suspend fun getTags(includeArchived: Boolean = false): List<Tag>

    suspend fun addReceiptReference(
        localUri: String,
        displayName: String? = null,
        mimeType: String? = null,
        capturedAtEpochMillis: Long,
        sha256: String? = null,
    ): ReceiptReference

    suspend fun record(transaction: NewTransaction): LedgerTransaction
    suspend fun getTransaction(id: Long): LedgerTransaction?
    suspend fun getTransactions(): List<LedgerTransaction>

    /** Expense-only totals. Income and transfers are deliberately excluded. */
    suspend fun getExpenseTotals(
        fromEpochMillisInclusive: Long,
        toEpochMillisExclusive: Long,
    ): List<CurrencyTotal>
}
