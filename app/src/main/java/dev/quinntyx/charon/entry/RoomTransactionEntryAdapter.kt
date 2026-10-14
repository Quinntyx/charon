package dev.quinntyx.charon.entry

import dev.quinntyx.charon.domain.LedgerRepository
import dev.quinntyx.charon.domain.LedgerTransaction
import dev.quinntyx.charon.domain.NewTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Currency

class RoomTransactionEntryAdapter(
    private val repository: LedgerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun config(initialDraft: TransactionEntryDraft = TransactionEntryDraft()): TransactionEntryConfig =
        TransactionEntryConfig(
            folders = repository.getAccounts().map { EntryFolder(it.id.toString(), it.name, it.currencyCode) },
            tags = repository.getTags().map { EntryTag(it.id.toString(), it.name) },
            initialDraft = initialDraft,
        )

    suspend fun draftFor(transactionId: Long): TransactionEntryDraft {
        val transaction = requireNotNull(repository.getTransaction(transactionId)) { "Unknown transaction id $transactionId" }
        return transaction.toDraft()
    }

    suspend fun save(entry: ValidatedTransactionEntry): LedgerTransaction {
        val existing = entry.transactionId?.toLongOrNull()?.let { repository.getTransaction(it) }
        val merchantId = if (entry.kind == TransactionKind.TRANSFER) null
        else repository.getOrCreateMerchant(entry.merchant).id
        val receiptId = when {
            entry.receipt == null -> null
            existing?.receipt?.localUri == entry.receipt.uri -> existing.receipt.id
            else -> repository.addReceiptReference(
                localUri = entry.receipt.uri,
                displayName = entry.receipt.displayName,
                capturedAtEpochMillis = nowEpochMillis(),
            ).id
        }
        val occurredAt = entry.date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val tagIds = entry.tagIds.map(String::toLong).toSet()
        val transaction = when (entry.kind) {
            TransactionKind.EXPENSE -> NewTransaction.Expense(
                amountMinor = entry.amountMinor,
                currencyCode = entry.currencyCode,
                occurredAtEpochMillis = occurredAt,
                sourceAccountId = entry.folderId.toLong(),
                merchantId = merchantId,
                receiptId = receiptId,
                tagIds = tagIds,
            )
            TransactionKind.INCOME -> NewTransaction.Income(
                amountMinor = entry.amountMinor,
                currencyCode = entry.currencyCode,
                occurredAtEpochMillis = occurredAt,
                destinationAccountId = entry.folderId.toLong(),
                merchantId = merchantId,
                receiptId = receiptId,
                tagIds = tagIds,
            )
            TransactionKind.TRANSFER -> NewTransaction.Transfer(
                amountMinor = entry.amountMinor,
                currencyCode = entry.currencyCode,
                occurredAtEpochMillis = occurredAt,
                sourceAccountId = entry.folderId.toLong(),
                destinationAccountId = requireNotNull(entry.transferDestinationFolderId).toLong(),
                receiptId = receiptId,
                tagIds = tagIds,
            )
        }
        return if (existing == null) repository.record(transaction)
        else repository.updateTransaction(existing.id, transaction)
    }

    private fun LedgerTransaction.toDraft() = TransactionEntryDraft(
        transactionId = id.toString(),
        kind = when (kind) {
            dev.quinntyx.charon.domain.TransactionKind.EXPENSE -> TransactionKind.EXPENSE
            dev.quinntyx.charon.domain.TransactionKind.INCOME -> TransactionKind.INCOME
            dev.quinntyx.charon.domain.TransactionKind.TRANSFER -> TransactionKind.TRANSFER
        },
        amount = formatEditableAmount(amountMinor, currencyCode),
        date = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(zoneId).toLocalDate().toString(),
        merchant = merchant?.name ?: if (kind == dev.quinntyx.charon.domain.TransactionKind.TRANSFER) "Transfer" else "",
        currencyCode = currencyCode,
        selectedTagIds = tags.mapTo(mutableSetOf()) { it.id.toString() },
        transferDestinationFolderId = destinationAccount?.id?.toString(),
        receipt = receipt?.let { ReceiptAttachment(it.localUri, it.displayName) },
    )
}

internal fun formatEditableAmount(amountMinor: Long, currencyCode: String): String {
    val fractionDigits = Currency.getInstance(currencyCode).defaultFractionDigits.coerceAtLeast(0)
    return BigDecimal.valueOf(amountMinor, fractionDigits).toPlainString()
}
