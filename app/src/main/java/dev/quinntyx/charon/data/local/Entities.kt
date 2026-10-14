package dev.quinntyx.charon.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["normalized_name", "currency_code"], unique = true)],
)
internal data class AccountEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
)

@Entity(
    tableName = "merchants",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class TagEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "archived") val archived: Boolean = false,
)

@Entity(
    tableName = "receipt_references",
    indices = [Index(value = ["local_uri"], unique = true)],
)
internal data class ReceiptReferenceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "local_uri") val localUri: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "captured_at_epoch_millis") val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "sha256") val sha256: String?,
)

@Entity(
    tableName = "ledger_transactions",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["source_account_id"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(AccountEntity::class, ["id"], ["destination_account_id"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(MerchantEntity::class, ["id"], ["merchant_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(ReceiptReferenceEntity::class, ["id"], ["receipt_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["source_account_id"]),
        Index(value = ["destination_account_id"]),
        Index(value = ["merchant_id"]),
        Index(value = ["receipt_id"], unique = true),
        Index(value = ["occurred_at_epoch_millis"]),
    ],
)
internal data class LedgerTransactionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "source_account_id") val sourceAccountId: Long?,
    @ColumnInfo(name = "destination_account_id") val destinationAccountId: Long?,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "receipt_id") val receiptId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transaction_id", "tag_id"],
    foreignKeys = [
        ForeignKey(LedgerTransactionEntity::class, ["id"], ["transaction_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [Index(value = ["tag_id"])],
)
internal data class TransactionTagEntity(
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

@Entity(
    tableName = "recurring_payments",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["folder_account_id"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(MerchantEntity::class, ["id"], ["merchant_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["folder_account_id"]), Index(value = ["merchant_id"])],
)
internal data class RecurringPaymentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "folder_account_id") val folderAccountId: Long,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "starts_on_epoch_day") val startsOnEpochDay: Long,
    @ColumnInfo(name = "rule_type") val ruleType: String,
    @ColumnInfo(name = "rule_interval") val ruleInterval: Int,
    @ColumnInfo(name = "logging_policy") val loggingPolicy: String,
    @ColumnInfo(name = "amount_revisions_json") val amountRevisionsJson: String,
    @ColumnInfo(name = "skipped_dates_json") val skippedDatesJson: String,
    @ColumnInfo(name = "pause_windows_json") val pauseWindowsJson: String,
    @ColumnInfo(name = "paused_since_epoch_day") val pausedSinceEpochDay: Long?,
    @ColumnInfo(name = "cancelled_from_epoch_day") val cancelledFromEpochDay: Long?,
)

@Entity(
    tableName = "recurring_payment_tags",
    primaryKeys = ["recurring_payment_id", "tag_id"],
    foreignKeys = [
        ForeignKey(RecurringPaymentEntity::class, ["id"], ["recurring_payment_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [Index(value = ["tag_id"])],
)
internal data class RecurringPaymentTagEntity(
    @ColumnInfo(name = "recurring_payment_id") val recurringPaymentId: String,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

@Entity(
    tableName = "logged_occurrences",
    primaryKeys = ["recurring_payment_id", "due_epoch_day"],
    foreignKeys = [
        ForeignKey(LedgerTransactionEntity::class, ["id"], ["ledger_transaction_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [Index(value = ["ledger_transaction_id"], unique = true)],
)
internal data class LoggedOccurrenceEntity(
    @ColumnInfo(name = "recurring_payment_id") val recurringPaymentId: String,
    @ColumnInfo(name = "due_epoch_day") val dueEpochDay: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "folder_account_id") val folderAccountId: Long,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "tag_ids_json") val tagIdsJson: String,
    @ColumnInfo(name = "logging_policy") val loggingPolicy: String,
    @ColumnInfo(name = "logged_at_epoch_millis") val loggedAtEpochMillis: Long,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "ledger_transaction_id") val ledgerTransactionId: Long,
)

internal data class ExpenseTotalRow(
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
)

internal data class AccountBalanceRow(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "balance_minor") val balanceMinor: Long,
)
