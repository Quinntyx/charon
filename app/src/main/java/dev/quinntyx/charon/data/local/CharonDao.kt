package dev.quinntyx.charon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface CharonDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccount(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE normalized_name = :normalizedName AND currency_code = :currencyCode")
    suspend fun findAccount(normalizedName: String, currencyCode: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE (:includeArchived = 1 OR archived = 0) ORDER BY name COLLATE NOCASE, id")
    suspend fun getAccounts(includeArchived: Boolean): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(merchant: MerchantEntity): Long

    @Update
    suspend fun updateMerchant(merchant: MerchantEntity)

    @Query("SELECT * FROM merchants WHERE id = :id")
    suspend fun getMerchant(id: Long): MerchantEntity?

    @Query("SELECT * FROM merchants WHERE normalized_name = :normalizedName")
    suspend fun findMerchant(normalizedName: String): MerchantEntity?

    @Query("SELECT * FROM merchants ORDER BY name COLLATE NOCASE, id")
    suspend fun getMerchants(): List<MerchantEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTag(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName")
    suspend fun findTag(normalizedName: String): TagEntity?

    @Query("SELECT * FROM tags WHERE (:includeArchived = 1 OR archived = 0) ORDER BY name COLLATE NOCASE, id")
    suspend fun getTags(includeArchived: Boolean): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id IN (:ids)")
    suspend fun getTags(ids: Set<Long>): List<TagEntity>

    @Insert
    suspend fun insertReceipt(receipt: ReceiptReferenceEntity): Long

    @Query("SELECT * FROM receipt_references WHERE id = :id")
    suspend fun getReceipt(id: Long): ReceiptReferenceEntity?

    @Query("SELECT COUNT(*) FROM ledger_transactions WHERE receipt_id = :receiptId")
    suspend fun countTransactionsUsingReceipt(receiptId: Long): Int

    @Insert
    suspend fun insertTransaction(transaction: LedgerTransactionEntity): Long

    @Insert
    suspend fun insertTransactionTags(tags: List<TransactionTagEntity>)

    @Query("SELECT * FROM ledger_transactions WHERE id = :id")
    suspend fun getTransaction(id: Long): LedgerTransactionEntity?

    @Query("SELECT * FROM ledger_transactions ORDER BY occurred_at_epoch_millis DESC, id DESC")
    suspend fun getTransactions(): List<LedgerTransactionEntity>

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN transaction_tags ON tags.id = transaction_tags.tag_id
        WHERE transaction_tags.transaction_id = :transactionId
        ORDER BY tags.name COLLATE NOCASE, tags.id
        """,
    )
    suspend fun getTagsForTransaction(transactionId: Long): List<TagEntity>

    @Query(
        """
        SELECT currency_code, SUM(amount_minor) AS amount_minor
        FROM ledger_transactions
        WHERE kind = 'EXPENSE'
          AND occurred_at_epoch_millis >= :fromEpochMillisInclusive
          AND occurred_at_epoch_millis < :toEpochMillisExclusive
        GROUP BY currency_code
        ORDER BY currency_code
        """,
    )
    suspend fun getExpenseTotals(
        fromEpochMillisInclusive: Long,
        toEpochMillisExclusive: Long,
    ): List<ExpenseTotalRow>
}
