package dev.quinntyx.charon.domain

enum class TransactionKind {
    EXPENSE,
    INCOME,
    TRANSFER,
}

data class Account(
    val id: Long,
    val name: String,
    val currencyCode: String,
    val archived: Boolean,
)

data class Merchant(
    val id: Long,
    val name: String,
)

data class Tag(
    val id: Long,
    val name: String,
    val archived: Boolean,
)

data class ReceiptReference(
    val id: Long,
    val localUri: String,
    val displayName: String?,
    val mimeType: String?,
    val capturedAtEpochMillis: Long,
    val sha256: String?,
)

data class LedgerTransaction(
    val id: Long,
    val kind: TransactionKind,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAtEpochMillis: Long,
    val sourceAccount: Account?,
    val destinationAccount: Account?,
    val merchant: Merchant?,
    val receipt: ReceiptReference?,
    val tags: List<Tag>,
    val note: String?,
    val createdAtEpochMillis: Long,
)

data class CurrencyTotal(
    val currencyCode: String,
    val amountMinor: Long,
)

sealed interface NewTransaction {
    val amountMinor: Long
    val currencyCode: String
    val occurredAtEpochMillis: Long
    val receiptId: Long?
    val tagIds: Set<Long>
    val note: String?

    data class Expense(
        override val amountMinor: Long,
        override val currencyCode: String,
        override val occurredAtEpochMillis: Long,
        val sourceAccountId: Long,
        val merchantId: Long? = null,
        override val receiptId: Long? = null,
        override val tagIds: Set<Long> = emptySet(),
        override val note: String? = null,
    ) : NewTransaction

    data class Income(
        override val amountMinor: Long,
        override val currencyCode: String,
        override val occurredAtEpochMillis: Long,
        val destinationAccountId: Long,
        val merchantId: Long? = null,
        override val receiptId: Long? = null,
        override val tagIds: Set<Long> = emptySet(),
        override val note: String? = null,
    ) : NewTransaction

    data class Transfer(
        override val amountMinor: Long,
        override val currencyCode: String,
        override val occurredAtEpochMillis: Long,
        val sourceAccountId: Long,
        val destinationAccountId: Long,
        override val receiptId: Long? = null,
        override val tagIds: Set<Long> = emptySet(),
        override val note: String? = null,
    ) : NewTransaction
}
