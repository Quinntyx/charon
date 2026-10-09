package dev.quinntyx.charon.receipt.ocr

import java.time.LocalDate

/** One line returned by the on-device recognizer, in visual reading order. */
data class ReceiptTextLine(
    val text: String,
    val blockIndex: Int,
    val lineIndex: Int,
)

/** OCR output shared by the recognizer and parser without retaining a receipt image. */
data class ReceiptText(
    val rawText: String,
    val lines: List<ReceiptTextLine>,
) {
    companion object {
        fun fromPlainText(text: String): ReceiptText {
            val lines = text.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapIndexed { index, line -> ReceiptTextLine(line, 0, index) }
                .toList()
            return ReceiptText(text, lines)
        }
    }
}

@JvmInline
value class CurrencyCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(value: String): CurrencyCode {
            val normalized = value.trim().uppercase()
            require(normalized.matches(Regex("[A-Z]{3}"))) { "Currency codes must be ISO-style three-letter codes" }
            return CurrencyCode(normalized)
        }
    }
}

/** Money is always represented in the currency's integer minor units. */
data class ParsedMoney(val minorUnits: Long)

enum class SuggestionConfidence { HIGH, MEDIUM, LOW }

data class ReceiptSuggestion<T>(
    val value: T,
    val sourceLine: String,
    val confidence: SuggestionConfidence,
    val alternatives: List<T> = emptyList(),
)

enum class ReceiptParseIssue {
    MISSING_MERCHANT,
    AMBIGUOUS_MERCHANT,
    MISSING_TOTAL,
    AMBIGUOUS_TOTAL,
    MISSING_DATE,
    AMBIGUOUS_DATE,
    MISSING_CURRENCY,
    AMBIGUOUS_CURRENCY,
}

data class ParsedReceipt(
    val merchant: ReceiptSuggestion<String>?,
    val total: ReceiptSuggestion<ParsedMoney>?,
    val date: ReceiptSuggestion<LocalDate>?,
    val currency: ReceiptSuggestion<CurrencyCode>?,
    val issues: Set<ReceiptParseIssue>,
) {
    val requiresReview: Boolean get() = issues.isNotEmpty()
}
