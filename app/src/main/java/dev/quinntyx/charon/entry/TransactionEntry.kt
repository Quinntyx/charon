package dev.quinntyx.charon.entry

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.Locale

/** The accounting meaning of a recorded transaction. */
enum class TransactionKind {
    EXPENSE,
    INCOME,
    TRANSFER,
}

/** A local account/source that can be tapped to save an entry. */
data class EntryFolder(
    val id: String,
    val name: String,
    val currencyCode: String,
)

data class EntryTag(
    val id: String,
    val name: String,
)

/** A receipt that remains on the device. Network URI schemes are rejected during validation. */
data class ReceiptAttachment(
    val uri: String,
    val displayName: String? = null,
)

/** Editable values proposed by the OCR subsystem. */
data class OcrEntrySuggestion(
    val amount: String? = null,
    val date: String? = null,
    val merchant: String? = null,
    val currencyCode: String? = null,
)

data class TransactionEntryDraft(
    val transactionId: String? = null,
    val kind: TransactionKind = TransactionKind.EXPENSE,
    val amount: String = "",
    val date: String = "",
    val merchant: String = "",
    val currencyCode: String = "",
    val selectedTagIds: Set<String> = emptySet(),
    val transferDestinationFolderId: String? = null,
    val receipt: ReceiptAttachment? = null,
) {
    /** OCR only fills blank fields, so existing edits are never overwritten. */
    fun withOcrSuggestion(suggestion: OcrEntrySuggestion?): TransactionEntryDraft {
        if (suggestion == null) return this
        return copy(
            amount = amount.ifBlank { suggestion.amount.orEmpty() },
            date = date.ifBlank { suggestion.date.orEmpty() },
            merchant = merchant.ifBlank { suggestion.merchant.orEmpty() },
            currencyCode = currencyCode.ifBlank { suggestion.currencyCode.orEmpty() },
        )
    }
}

/**
 * The persistence-facing result of the entry form. Amounts are integer minor units; no floating
 * point value crosses this API. For transfers, [folderId] is the source and
 * [transferDestinationFolderId] is the destination.
 */
data class ValidatedTransactionEntry(
    val transactionId: String?,
    val kind: TransactionKind,
    val amountMinor: Long,
    val date: LocalDate,
    val merchant: String,
    val currencyCode: String,
    val folderId: String,
    val tagIds: Set<String>,
    val transferDestinationFolderId: String?,
    val receipt: ReceiptAttachment?,
)

enum class EntryField {
    AMOUNT,
    DATE,
    MERCHANT,
    CURRENCY,
    SAVE_FOLDER,
    TRANSFER_DESTINATION,
    TAGS,
    RECEIPT,
}

sealed interface EntryValidationResult {
    data class Valid(val entry: ValidatedTransactionEntry) : EntryValidationResult

    data class Invalid(val errors: Map<EntryField, String>) : EntryValidationResult
}

sealed interface AmountParseResult {
    data class Valid(val minorUnits: Long) : AmountParseResult

    data class Invalid(val reason: String) : AmountParseResult
}

/** Strict decimal-to-minor-unit conversion without Double or Float rounding. */
object MinorUnitAmountParser {
    private val decimalPattern = Regex("[0-9]+(?:[.,][0-9]+)?")

    fun parse(input: String, currencyCode: String): AmountParseResult {
        val currency = currencyOrNull(currencyCode)
            ?: return AmountParseResult.Invalid("Use a valid three-letter currency code.")
        val normalized = input.trim()
        if (!decimalPattern.matches(normalized)) {
            return AmountParseResult.Invalid("Enter a positive amount without grouping separators.")
        }

        val amount = try {
            BigDecimal(normalized.replace(',', '.'))
        } catch (_: NumberFormatException) {
            return AmountParseResult.Invalid("Enter a valid amount.")
        }
        if (amount.signum() <= 0) {
            return AmountParseResult.Invalid("Amount must be greater than zero.")
        }

        val minorUnits = try {
            amount.movePointRight(currency.defaultFractionDigits).longValueExact()
        } catch (_: ArithmeticException) {
            return AmountParseResult.Invalid(
                "Amount has more than ${currency.defaultFractionDigits} decimal places or is too large.",
            )
        }
        return AmountParseResult.Valid(minorUnits)
    }
}

object TransactionEntryValidator {
    private const val MAX_MERCHANT_LENGTH = 120
    private val localReceiptScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun validate(
        draft: TransactionEntryDraft,
        tappedFolder: EntryFolder,
        availableFolders: List<EntryFolder>,
        availableTags: List<EntryTag>,
    ): EntryValidationResult {
        val errors = linkedMapOf<EntryField, String>()
        val currency = currencyOrNull(draft.currencyCode)
        val normalizedCurrency = currency?.currencyCode
        if (currency == null) {
            errors[EntryField.CURRENCY] = "Use a valid three-letter currency code."
        }

        val amountMinor = when (val parsed = MinorUnitAmountParser.parse(draft.amount, draft.currencyCode)) {
            is AmountParseResult.Valid -> parsed.minorUnits
            is AmountParseResult.Invalid -> {
                errors[EntryField.AMOUNT] = parsed.reason
                null
            }
        }

        val date = try {
            LocalDate.parse(draft.date.trim())
        } catch (_: DateTimeParseException) {
            errors[EntryField.DATE] = "Use a real date in YYYY-MM-DD format."
            null
        }

        val merchant = draft.merchant.trim()
        when {
            merchant.isEmpty() -> errors[EntryField.MERCHANT] = "Merchant or payer is required."
            merchant.length > MAX_MERCHANT_LENGTH -> {
                errors[EntryField.MERCHANT] = "Merchant or payer must be $MAX_MERCHANT_LENGTH characters or fewer."
            }
            merchant.any { it.isISOControl() } -> {
                errors[EntryField.MERCHANT] = "Merchant or payer cannot contain line breaks or control characters."
            }
        }

        val knownFolder = availableFolders.singleOrNull { it.id == tappedFolder.id }
        when {
            knownFolder == null -> errors[EntryField.SAVE_FOLDER] = "Choose an available folder."
            normalizedCurrency != null &&
                normalizeCurrencyCode(knownFolder.currencyCode) != normalizedCurrency -> {
                errors[EntryField.SAVE_FOLDER] = "The tapped folder must use $normalizedCurrency."
            }
        }

        val unknownTags = draft.selectedTagIds - availableTags.mapTo(mutableSetOf()) { it.id }
        if (unknownTags.isNotEmpty()) {
            errors[EntryField.TAGS] = "One or more selected tags are no longer available."
        }

        val transferDestination = if (draft.kind == TransactionKind.TRANSFER) {
            availableFolders.singleOrNull { it.id == draft.transferDestinationFolderId }.also { destination ->
                when {
                    destination == null -> {
                        errors[EntryField.TRANSFER_DESTINATION] = "Choose a destination folder."
                    }
                    destination.id == tappedFolder.id -> {
                        errors[EntryField.TRANSFER_DESTINATION] = "Source and destination folders must differ."
                    }
                    normalizedCurrency != null &&
                        normalizeCurrencyCode(destination.currencyCode) != normalizedCurrency -> {
                        errors[EntryField.TRANSFER_DESTINATION] =
                            "Transfer folders must both use $normalizedCurrency."
                    }
                }
            }
        } else {
            null
        }

        draft.receipt?.let { receipt ->
            val scheme = localReceiptScheme.find(receipt.uri.trim())?.value?.dropLast(1)?.lowercase(Locale.ROOT)
            if (scheme !in setOf("content", "file", "android.resource")) {
                errors[EntryField.RECEIPT] = "Receipt must use a local on-device URI."
            }
        }

        if (errors.isNotEmpty() || amountMinor == null || date == null || normalizedCurrency == null || knownFolder == null) {
            return EntryValidationResult.Invalid(errors)
        }

        return EntryValidationResult.Valid(
            ValidatedTransactionEntry(
                transactionId = draft.transactionId,
                kind = draft.kind,
                amountMinor = amountMinor,
                date = date,
                merchant = merchant,
                currencyCode = normalizedCurrency,
                folderId = knownFolder.id,
                tagIds = draft.selectedTagIds.toSet(),
                transferDestinationFolderId = transferDestination?.id,
                receipt = draft.receipt,
            ),
        )
    }
}

private fun currencyOrNull(code: String): Currency? {
    val normalized = normalizeCurrencyCode(code)
    if (!Regex("[A-Z]{3}").matches(normalized)) return null
    val currency = try {
        Currency.getInstance(normalized)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return currency.takeIf { it.defaultFractionDigits >= 0 }
}

private fun normalizeCurrencyCode(code: String): String = code.trim().uppercase(Locale.ROOT)
