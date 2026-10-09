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
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    @ColumnInfo(name = "archived")
    val archived: Boolean = false,
)

@Entity(
    tableName = "merchants",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class MerchantEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "archived")
    val archived: Boolean = false,
)

@Entity(
    tableName = "receipt_references",
    indices = [Index(value = ["local_uri"], unique = true)],
)
internal data class ReceiptReferenceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "local_uri")
    val localUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "sha256")
    val sha256: String?,
)

@Entity(
    tableName = "ledger_transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_account_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["destination_account_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = MerchantEntity::class,
            parentColumns = ["id"],
            childColumns = ["merchant_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ReceiptReferenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["receipt_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
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
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "source_account_id")
    val sourceAccountId: Long?,
    @ColumnInfo(name = "destination_account_id")
    val destinationAccountId: Long?,
    @ColumnInfo(name = "merchant_id")
    val merchantId: Long?,
    @ColumnInfo(name = "receipt_id")
    val receiptId: Long?,
    @ColumnInfo(name = "note")
    val note: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transaction_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["tag_id"])],
)
internal data class TransactionTagEntity(
    @ColumnInfo(name = "transaction_id")
    val transactionId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)

internal data class ExpenseTotalRow(
    @ColumnInfo(name = "currency_code")
    val currencyCode: String,
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,
)
