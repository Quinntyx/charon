package dev.quinntyx.charon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface CharonDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Update suspend fun updateAccount(account: AccountEntity)
    @Upsert suspend fun upsertAccount(account: AccountEntity)
    @Query("DELETE FROM accounts WHERE id = :id") suspend fun deleteAccount(id: Long): Int
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun getAccount(id: Long): AccountEntity?
    @Query("SELECT * FROM accounts WHERE normalized_name = :normalizedName AND currency_code = :currencyCode")
    suspend fun findAccount(normalizedName: String, currencyCode: String): AccountEntity?
    @Query("SELECT * FROM accounts WHERE (:includeArchived = 1 OR archived = 0) ORDER BY name COLLATE NOCASE, id")
    suspend fun getAccounts(includeArchived: Boolean): List<AccountEntity>
    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE, id") fun observeAccounts(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(merchant: MerchantEntity): Long
    @Update suspend fun updateMerchant(merchant: MerchantEntity)
    @Upsert suspend fun upsertMerchant(merchant: MerchantEntity)
    @Query("SELECT * FROM merchants WHERE id = :id") suspend fun getMerchant(id: Long): MerchantEntity?
    @Query("SELECT * FROM merchants WHERE normalized_name = :normalizedName") suspend fun findMerchant(normalizedName: String): MerchantEntity?
    @Query("SELECT * FROM merchants ORDER BY name COLLATE NOCASE, id") suspend fun getMerchants(): List<MerchantEntity>
    @Query("SELECT * FROM merchants ORDER BY name COLLATE NOCASE, id") fun observeMerchants(): Flow<List<MerchantEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long
    @Update suspend fun updateTag(tag: TagEntity)
    @Upsert suspend fun upsertTag(tag: TagEntity)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTag(id: Long): Int
    @Query("SELECT * FROM tags WHERE id = :id") suspend fun getTag(id: Long): TagEntity?
    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName") suspend fun findTag(normalizedName: String): TagEntity?
    @Query("SELECT * FROM tags WHERE (:includeArchived = 1 OR archived = 0) ORDER BY name COLLATE NOCASE, id")
    suspend fun getTags(includeArchived: Boolean): List<TagEntity>
    @Query("SELECT * FROM tags WHERE id IN (:ids)") suspend fun getTags(ids: Set<Long>): List<TagEntity>
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id") fun observeTags(): Flow<List<TagEntity>>

    @Insert suspend fun insertReceipt(receipt: ReceiptReferenceEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReceiptIgnoring(receipt: ReceiptReferenceEntity): Long
    @Upsert suspend fun upsertReceipt(receipt: ReceiptReferenceEntity)
    @Query("SELECT * FROM receipt_references WHERE id = :id") suspend fun getReceipt(id: Long): ReceiptReferenceEntity?
    @Query("SELECT * FROM receipt_references ORDER BY id") suspend fun getReceipts(): List<ReceiptReferenceEntity>
    @Query("SELECT COUNT(*) FROM ledger_transactions WHERE receipt_id = :receiptId")
    suspend fun countTransactionsUsingReceipt(receiptId: Long): Int
    @Query("SELECT COUNT(*) FROM ledger_transactions WHERE receipt_id = :receiptId AND id != :transactionId")
    suspend fun countOtherTransactionsUsingReceipt(receiptId: Long, transactionId: Long): Int

    @Insert suspend fun insertTransaction(transaction: LedgerTransactionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTransactionIgnoring(transaction: LedgerTransactionEntity): Long
    @Upsert suspend fun upsertTransaction(transaction: LedgerTransactionEntity)
    @Update suspend fun updateTransaction(transaction: LedgerTransactionEntity)
    @Insert suspend fun insertTransactionTags(tags: List<TransactionTagEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTransactionTagsIgnoring(tags: List<TransactionTagEntity>)
    @Query("DELETE FROM transaction_tags WHERE transaction_id = :transactionId") suspend fun deleteTransactionTags(transactionId: Long)
    @Query("SELECT * FROM transaction_tags ORDER BY transaction_id, tag_id") suspend fun getAllTransactionTags(): List<TransactionTagEntity>
    @Query("SELECT * FROM ledger_transactions WHERE id = :id") suspend fun getTransaction(id: Long): LedgerTransactionEntity?
    @Query("SELECT * FROM ledger_transactions ORDER BY occurred_at_epoch_millis DESC, id DESC") suspend fun getTransactions(): List<LedgerTransactionEntity>
    @Query("SELECT * FROM ledger_transactions ORDER BY occurred_at_epoch_millis DESC, id DESC") fun observeTransactions(): Flow<List<LedgerTransactionEntity>>

    @Query("SELECT tags.* FROM tags INNER JOIN transaction_tags ON tags.id = transaction_tags.tag_id WHERE transaction_tags.transaction_id = :transactionId ORDER BY tags.name COLLATE NOCASE, tags.id")
    suspend fun getTagsForTransaction(transactionId: Long): List<TagEntity>

    @Query("SELECT COUNT(*) FROM ledger_transactions WHERE source_account_id = :accountId OR destination_account_id = :accountId")
    suspend fun countTransactionsForAccount(accountId: Long): Int
    @Query("SELECT COUNT(*) FROM transaction_tags WHERE tag_id = :tagId") suspend fun countTransactionsForTag(tagId: Long): Int
    @Query("SELECT COUNT(*) FROM recurring_payments WHERE folder_account_id = :accountId") suspend fun countRecurrencesForAccount(accountId: Long): Int
    @Query("SELECT COUNT(*) FROM recurring_payment_tags WHERE tag_id = :tagId") suspend fun countRecurrencesForTag(tagId: Long): Int
    @Query("SELECT COALESCE(SUM(CASE WHEN kind = 'INCOME' AND destination_account_id = :accountId THEN amount_minor WHEN kind = 'EXPENSE' AND source_account_id = :accountId THEN -amount_minor WHEN kind = 'TRANSFER' AND destination_account_id = :accountId THEN amount_minor WHEN kind = 'TRANSFER' AND source_account_id = :accountId THEN -amount_minor ELSE 0 END), 0) FROM ledger_transactions")
    suspend fun getAccountBalance(accountId: Long): Long

    @Query("SELECT currency_code, SUM(amount_minor) AS amount_minor FROM ledger_transactions WHERE kind = 'EXPENSE' AND occurred_at_epoch_millis >= :fromEpochMillisInclusive AND occurred_at_epoch_millis < :toEpochMillisExclusive GROUP BY currency_code ORDER BY currency_code")
    suspend fun getExpenseTotals(fromEpochMillisInclusive: Long, toEpochMillisExclusive: Long): List<ExpenseTotalRow>

    @Query("SELECT * FROM recurring_payments WHERE id = :id") suspend fun getRecurringPayment(id: String): RecurringPaymentEntity?
    @Query("SELECT * FROM recurring_payments ORDER BY title COLLATE NOCASE, id") suspend fun getRecurringPayments(): List<RecurringPaymentEntity>
    @Query("SELECT * FROM recurring_payments ORDER BY title COLLATE NOCASE, id") fun observeRecurringPayments(): Flow<List<RecurringPaymentEntity>>
    @Upsert suspend fun upsertRecurringPayment(payment: RecurringPaymentEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRecurringPaymentIgnoring(payment: RecurringPaymentEntity): Long
    @Query("DELETE FROM recurring_payment_tags WHERE recurring_payment_id = :paymentId") suspend fun deleteRecurringPaymentTags(paymentId: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRecurringPaymentTags(tags: List<RecurringPaymentTagEntity>)
    @Query("SELECT tag_id FROM recurring_payment_tags WHERE recurring_payment_id = :paymentId ORDER BY tag_id") suspend fun getRecurringPaymentTagIds(paymentId: String): List<Long>
    @Query("SELECT * FROM recurring_payment_tags ORDER BY recurring_payment_id, tag_id") suspend fun getAllRecurringPaymentTags(): List<RecurringPaymentTagEntity>

    @Query("SELECT * FROM logged_occurrences ORDER BY due_epoch_day DESC, title COLLATE NOCASE") suspend fun getLoggedOccurrences(): List<LoggedOccurrenceEntity>
    @Query("SELECT * FROM logged_occurrences ORDER BY due_epoch_day DESC, title COLLATE NOCASE") fun observeLoggedOccurrences(): Flow<List<LoggedOccurrenceEntity>>
    @Query("SELECT COUNT(*) FROM logged_occurrences WHERE recurring_payment_id = :paymentId AND due_epoch_day = :dueEpochDay") suspend fun countLoggedOccurrence(paymentId: String, dueEpochDay: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLoggedOccurrence(log: LoggedOccurrenceEntity): Long
    @Upsert suspend fun upsertLoggedOccurrence(log: LoggedOccurrenceEntity)
}
