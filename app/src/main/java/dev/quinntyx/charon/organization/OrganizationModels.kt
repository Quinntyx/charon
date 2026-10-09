package dev.quinntyx.charon.organization

import java.util.Currency
import java.util.Locale

private const val MAX_ORGANIZATION_NAME_LENGTH = 80

@JvmInline
value class FolderId(val value: String) {
    init {
        require(value.isNotBlank()) { "Folder id cannot be blank" }
    }
}

@JvmInline
value class TagId(val value: String) {
    init {
        require(value.isNotBlank()) { "Tag id cannot be blank" }
    }
}

data class CurrencyBalance(
    val currencyCode: String,
    val minorUnits: Long,
) {
    init {
        require(currencyCode.matches(Regex("[A-Z]{3}"))) {
            "Currency codes must be three uppercase letters"
        }
    }
}

data class FolderAccount(
    val id: FolderId,
    val name: String,
    val isArchived: Boolean = false,
    val balances: List<CurrencyBalance> = emptyList(),
    val transactionCount: Int = 0,
    val recurringRuleCount: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "Folder name cannot be blank" }
        require(transactionCount >= 0) { "Transaction count cannot be negative" }
        require(recurringRuleCount >= 0) { "Recurring-rule count cannot be negative" }
        require(balances.map { it.currencyCode }.distinct().size == balances.size) {
            "A folder can have only one balance per currency"
        }
    }
}

data class TransactionTag(
    val id: TagId,
    val name: String,
    val isArchived: Boolean = false,
    val transactionCount: Int = 0,
    val recurringRuleCount: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "Tag name cannot be blank" }
        require(transactionCount >= 0) { "Transaction count cannot be negative" }
        require(recurringRuleCount >= 0) { "Recurring-rule count cannot be negative" }
    }
}

data class OrganizationSnapshot(
    val folders: List<FolderAccount> = emptyList(),
    val tags: List<TransactionTag> = emptyList(),
) {
    val activeFolders: List<FolderAccount>
        get() = folders.filterNot { it.isArchived }.sortedFolderNames()

    val archivedFolders: List<FolderAccount>
        get() = folders.filter { it.isArchived }.sortedFolderNames()

    val activeTags: List<TransactionTag>
        get() = tags.filterNot { it.isArchived }.sortedTagNames()

    val archivedTags: List<TransactionTag>
        get() = tags.filter { it.isArchived }.sortedTagNames()
}

sealed interface OrganizationFailure {
    data class InvalidName(val message: String) : OrganizationFailure
    data class NameAlreadyExists(val name: String) : OrganizationFailure
    data object NotFound : OrganizationFailure

    data class DeletionBlocked(
        val transactionCount: Int,
        val recurringRuleCount: Int,
        val nonZeroBalances: List<CurrencyBalance> = emptyList(),
    ) : OrganizationFailure
}

sealed interface OrganizationResult<out T> {
    data class Success<T>(val value: T) : OrganizationResult<T>
    data class Failure(val reason: OrganizationFailure) : OrganizationResult<Nothing>
}

internal fun normalizeOrganizationName(rawName: String): OrganizationResult<String> {
    val normalized = rawName.trim().replace(Regex("\\s+"), " ")
    return when {
        normalized.isEmpty() -> OrganizationResult.Failure(
            OrganizationFailure.InvalidName("Name cannot be empty"),
        )

        normalized.length > MAX_ORGANIZATION_NAME_LENGTH -> OrganizationResult.Failure(
            OrganizationFailure.InvalidName(
                "Name must be $MAX_ORGANIZATION_NAME_LENGTH characters or fewer",
            ),
        )

        else -> OrganizationResult.Success(normalized)
    }
}

fun CurrencyBalance.displayText(locale: Locale = Locale.getDefault()): String {
    val fractionDigits = runCatching {
        Currency.getInstance(currencyCode).defaultFractionDigits
    }.getOrDefault(2).coerceAtLeast(0)
    val negative = minorUnits < 0
    val digits = minorUnits.toBigInteger().abs().toString().padStart(fractionDigits + 1, '0')
    val amount = if (fractionDigits == 0) {
        digits
    } else {
        val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        "${digits.dropLast(fractionDigits)}$separator${digits.takeLast(fractionDigits)}"
    }
    return "$currencyCode ${if (negative) "-" else ""}$amount"
}

private fun List<FolderAccount>.sortedFolderNames(): List<FolderAccount> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

private fun List<TransactionTag>.sortedTagNames(): List<TransactionTag> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
