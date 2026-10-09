package dev.quinntyx.charon.receipt.ocr

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.max

/** Deterministic, side-effect-free parsing of ML Kit text into editable receipt suggestions. */
class ReceiptParser {
    fun parse(text: String): ParsedReceipt = parse(ReceiptText.fromPlainText(text))

    fun parse(receiptText: ReceiptText): ParsedReceipt {
        val lines = receiptText.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        val currencyResult = findCurrency(lines)
        val totalResult = findTotal(lines, currencyResult.suggestion?.value)
        val merchantResult = findMerchant(lines)
        val dateResult = findDate(lines)

        val issues = buildSet {
            addResultIssue(merchantResult, ReceiptParseIssue.MISSING_MERCHANT, ReceiptParseIssue.AMBIGUOUS_MERCHANT)
            addResultIssue(totalResult, ReceiptParseIssue.MISSING_TOTAL, ReceiptParseIssue.AMBIGUOUS_TOTAL)
            addResultIssue(dateResult, ReceiptParseIssue.MISSING_DATE, ReceiptParseIssue.AMBIGUOUS_DATE)
            addResultIssue(currencyResult, ReceiptParseIssue.MISSING_CURRENCY, ReceiptParseIssue.AMBIGUOUS_CURRENCY)
        }
        return ParsedReceipt(
            merchant = merchantResult.suggestion,
            total = totalResult.suggestion,
            date = dateResult.suggestion,
            currency = currencyResult.suggestion,
            issues = issues,
        )
    }

    private fun MutableSet<ReceiptParseIssue>.addResultIssue(
        result: ParseResult<*>,
        missing: ReceiptParseIssue,
        ambiguous: ReceiptParseIssue,
    ) {
        if (result.suggestion == null) add(missing) else if (result.ambiguous) add(ambiguous)
    }

    private fun findCurrency(lines: List<String>): ParseResult<CurrencyCode> {
        val candidates = mutableListOf<Scored<CurrencyCode>>()
        lines.forEachIndexed { index, line ->
            explicitCurrencyRegex.findAll(line).forEach { match ->
                candidates += Scored(CurrencyCode.of(match.value), 120, line, index)
            }
            prefixedCurrencyRegex.findAll(line).forEach { match ->
                prefixedCurrencyCodes[match.value.uppercase(Locale.ROOT)]?.let { code ->
                    candidates += Scored(CurrencyCode.of(code), 110, line, index)
                }
            }
            symbolCurrencyCodes.forEach { (symbol, codes) ->
                if (line.contains(symbol)) {
                    codes.forEach { code -> candidates += Scored(CurrencyCode.of(code), 70, line, index) }
                }
            }
        }
        if (candidates.isEmpty()) return ParseResult(null, false)

        val ranked = candidates
            .groupBy { it.value }
            .map { (value, matches) ->
                val best = matches.maxBy { it.score }
                best.copy(score = best.score + (matches.size - 1).coerceAtMost(3))
            }
            .sortedWith(scoredComparator())
        val top = ranked.first()
        val alternatives = ranked.drop(1).filter { it.score >= top.score - 5 }.map { it.value }
        return ParseResult(
            suggestion = ReceiptSuggestion(
                value = top.value,
                sourceLine = top.line,
                confidence = confidence(top.score, high = 105, medium = 75),
                alternatives = alternatives,
            ),
            ambiguous = alternatives.isNotEmpty(),
        )
    }

    private fun findTotal(lines: List<String>, currency: CurrencyCode?): ParseResult<ParsedMoney> {
        val candidates = mutableListOf<Scored<ParsedMoney>>()
        lines.forEachIndexed { index, line ->
            val normalized = line.lowercase(Locale.ROOT)
            if (excludedTotalLabels.any(normalized::contains)) return@forEachIndexed

            val labelScore = totalLabels.firstNotNullOfOrNull { (label, score) ->
                if (label.containsMatchIn(normalized)) score else null
            }
            if (labelScore == null) return@forEachIndexed

            val hasCurrencyMarker = containsCurrencyMarker(line)
            extractNumberTokens(line).forEach { token ->
                val parsed = parseMinorUnits(token, currency) ?: return@forEach
                val score = labelScore + if (hasCurrencyMarker) 5 else 0
                candidates += Scored(ParsedMoney(parsed), score, line, index)
            }
        }
        if (candidates.isEmpty()) return ParseResult(null, false)

        val ranked = candidates
            .groupBy { it.value }
            .map { (_, matches) -> matches.sortedWith(scoredComparator()).first() }
            .sortedWith(scoredComparator())
        val top = ranked.first()
        val alternatives = ranked.drop(1)
            .filter { it.score >= max(90, top.score - 8) }
            .map { it.value }
        return ParseResult(
            suggestion = ReceiptSuggestion(
                value = top.value,
                sourceLine = top.line,
                confidence = confidence(top.score, high = 100, medium = 70),
                alternatives = alternatives,
            ),
            ambiguous = alternatives.isNotEmpty(),
        )
    }

    private fun findMerchant(lines: List<String>): ParseResult<String> {
        val candidates = lines.take(8).mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            val lower = trimmed.lowercase(Locale.ROOT)
            if (trimmed.length !in 2..80 || merchantNoise.any(lower::contains) ||
                !trimmed.any(Char::isLetter) || looksLikeAddress(trimmed) ||
                dateRegexes.any { it.containsMatchIn(trimmed) } || containsCurrencyMarker(trimmed) ||
                extractNumberTokens(trimmed).isNotEmpty()
            ) {
                return@mapIndexedNotNull null
            }
            val letterCount = trimmed.count(Char::isLetter)
            val uppercaseCount = trimmed.count(Char::isUpperCase)
            val uppercaseBonus = if (letterCount > 2 && uppercaseCount * 4 >= letterCount * 3) 8 else 0
            Scored(trimmed, 100 - index * 8 + uppercaseBonus, trimmed, index)
        }.sortedWith(scoredComparator())

        if (candidates.isEmpty()) return ParseResult(null, false)
        val top = candidates.first()
        val alternatives = candidates.drop(1).filter { it.score >= top.score - 4 }.map { it.value }
        return ParseResult(
            suggestion = ReceiptSuggestion(
                value = top.value,
                sourceLine = top.line,
                confidence = confidence(top.score, high = 95, medium = 75),
                alternatives = alternatives,
            ),
            ambiguous = alternatives.isNotEmpty(),
        )
    }

    private fun findDate(lines: List<String>): ParseResult<LocalDate> {
        val candidates = mutableListOf<Scored<LocalDate>>()
        lines.forEachIndexed { index, line ->
            val lower = line.lowercase(Locale.ROOT)
            if (excludedDateLabels.any(lower::contains)) return@forEachIndexed
            val baseScore = if (dateLabelRegex.containsMatchIn(lower)) 105 else 75 - index.coerceAtMost(10)

            isoDateRegex.findAll(line).forEach { match ->
                parseDate(match.value, isoDateFormatters)?.let { candidates += Scored(it, baseScore, line, index) }
            }
            namedDateRegexes.forEachIndexed { formatterIndex, regex ->
                regex.findAll(line).forEach { match ->
                    parseDate(match.value, namedDateFormatters[formatterIndex])?.let {
                        candidates += Scored(it, baseScore, line, index)
                    }
                }
            }
            numericDateRegex.findAll(line).forEach { match ->
                val first = match.groupValues[1].toIntOrNull() ?: return@forEach
                val second = match.groupValues[2].toIntOrNull() ?: return@forEach
                val year = normalizedYear(match.groupValues[3].toIntOrNull() ?: return@forEach)
                val possibilities = when {
                    first > 12 -> listOf(Triple(year, second, first))
                    second > 12 -> listOf(Triple(year, first, second))
                    first == second -> listOf(Triple(year, first, second))
                    else -> listOf(Triple(year, first, second), Triple(year, second, first))
                }
                possibilities.forEach { (y, month, day) ->
                    validDate(y, month, day)?.let { candidates += Scored(it, baseScore, line, index) }
                }
            }
        }
        if (candidates.isEmpty()) return ParseResult(null, false)

        val ranked = candidates
            .groupBy { it.value }
            .map { (_, matches) -> matches.sortedWith(scoredComparator()).first() }
            .sortedWith(scoredComparator())
        val top = ranked.first()
        val alternatives = ranked.drop(1).filter { it.score >= top.score - 5 }.map { it.value }
        return ParseResult(
            suggestion = ReceiptSuggestion(
                value = top.value,
                sourceLine = top.line,
                confidence = confidence(top.score, high = 100, medium = 70),
                alternatives = alternatives,
            ),
            ambiguous = alternatives.isNotEmpty(),
        )
    }

    private fun extractNumberTokens(line: String): List<String> = numberRegex.findAll(line)
        .map { it.value.trim() }
        .filterNot { token ->
            token.length == 4 && token.all(Char::isDigit) && token.toIntOrNull() in 1900..2100
        }
        .toList()

    private fun parseMinorUnits(rawToken: String, currency: CurrencyCode?): Long? {
        val token = rawToken.replace(" ", "")
        if (token.isEmpty()) return null
        val scale = currencyMinorUnits[currency?.value] ?: 2
        val dot = token.lastIndexOf('.')
        val comma = token.lastIndexOf(',')
        val decimalSeparator = when {
            dot >= 0 && comma >= 0 -> if (dot > comma) '.' else ','
            dot >= 0 -> separatorRole(token, '.', scale)
            comma >= 0 -> separatorRole(token, ',', scale)
            else -> null
        }
        val normalized = buildString {
            token.forEach { character ->
                when {
                    character.isDigit() -> append(character)
                    character == decimalSeparator -> append('.')
                }
            }
        }
        if (normalized.count { it == '.' } > 1) return null
        val amount = normalized.toBigDecimalOrNull() ?: return null
        return try {
            amount.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun separatorRole(token: String, separator: Char, scale: Int): Char? {
        val occurrences = token.count { it == separator }
        val trailingDigits = token.length - token.lastIndexOf(separator) - 1
        return if (scale > 0 && trailingDigits in 1..scale && occurrences == 1) separator else null
    }

    private fun containsCurrencyMarker(line: String): Boolean =
        explicitCurrencyRegex.containsMatchIn(line) || prefixedCurrencyRegex.containsMatchIn(line) ||
            symbolCurrencyCodes.keys.any(line::contains)

    private fun looksLikeAddress(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return addressRegex.containsMatchIn(lower) || phoneRegex.containsMatchIn(line) ||
            lower.contains("www.") || lower.contains("@")
    }

    private fun parseDate(value: String, formatters: List<DateTimeFormatter>): LocalDate? =
        formatters.firstNotNullOfOrNull { formatter ->
            try {
                LocalDate.parse(value.trim(), formatter)
            } catch (_: DateTimeException) {
                null
            }
        }

    private fun normalizedYear(year: Int): Int = if (year < 100) 2000 + year else year

    private fun validDate(year: Int, month: Int, day: Int): LocalDate? = try {
        if (year !in 1990..2100) null else LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun confidence(score: Int, high: Int, medium: Int): SuggestionConfidence = when {
        score >= high -> SuggestionConfidence.HIGH
        score >= medium -> SuggestionConfidence.MEDIUM
        else -> SuggestionConfidence.LOW
    }

    private data class ParseResult<T>(val suggestion: ReceiptSuggestion<T>?, val ambiguous: Boolean)
    private data class Scored<T>(val value: T, val score: Int, val line: String, val order: Int)

    private fun <T> scoredComparator(): Comparator<Scored<T>> =
        compareByDescending<Scored<T>> { it.score }.thenBy { it.order }.thenBy { it.line }

    private companion object {
        val explicitCurrencyRegex = Regex(
            "\\b(?:USD|EUR|GBP|JPY|CNY|CAD|AUD|NZD|HKD|SGD|INR|KRW|CHF|SEK|NOK|DKK|PLN|CZK|RUB|BRL|MXN|KWD)\\b",
            RegexOption.IGNORE_CASE,
        )
        val prefixedCurrencyRegex = Regex("(?:US\\$|CA\\$|C\\$|AU\\$|A\\$|NZ\\$|HK\\$|S\\$)", RegexOption.IGNORE_CASE)
        val prefixedCurrencyCodes = mapOf(
            "US$" to "USD", "CA$" to "CAD", "C$" to "CAD", "AU$" to "AUD",
            "A$" to "AUD", "NZ$" to "NZD", "HK$" to "HKD", "S$" to "SGD",
        )
        val symbolCurrencyCodes = linkedMapOf(
            "€" to listOf("EUR"), "£" to listOf("GBP"), "₹" to listOf("INR"),
            "₽" to listOf("RUB"), "₩" to listOf("KRW"), "¥" to listOf("JPY", "CNY"),
            "$" to listOf("USD", "CAD", "AUD", "NZD"),
        )
        val currencyMinorUnits = mapOf("JPY" to 0, "KRW" to 0, "KWD" to 3)

        val totalLabels = listOf(
            Regex("\\bgrand\\s+total\\b") to 125,
            Regex("\\b(?:amount|balance|total)\\s+due\\b") to 120,
            Regex("\\btotal\\s+amount\\b") to 115,
            Regex("\\bnet\\s+total\\b") to 110,
            Regex("\\btotal\\b") to 105,
            Regex("\\bto\\s+pay\\b") to 100,
        )
        val excludedTotalLabels = listOf(
            "subtotal", "sub-total", "change", "cash", "tender", "amount paid", "payment",
            "card", "tip", "gratuity", "tax", "vat", "discount", "savings", "refund",
        )
        val numberRegex = Regex("(?<![\\p{L}\\d])\\d+(?:[., ]\\d+)*(?![\\p{L}\\d])")

        val merchantNoise = listOf(
            "receipt", "invoice", "order", "thank you", "welcome", "cashier", "terminal",
            "total", "subtotal", "tax", "vat", "change", "date", "time", "customer copy",
        )
        val addressRegex = Regex("\\b(?:street|st\\.?|road|rd\\.?|avenue|ave\\.?|boulevard|blvd\\.?|lane|ln\\.?|drive|dr\\.?)\\b")
        val phoneRegex = Regex("(?:\\+?\\d[\\d ()-]{7,}\\d)")

        val dateLabelRegex = Regex("\\b(?:date|purchased|transaction)\\b")
        val excludedDateLabels = listOf("due date", "expiry", "expires", "best before")
        val isoDateRegex = Regex("\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b")
        val numericDateRegex = Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b")
        val namedDateRegexes = listOf(
            Regex("\\b[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4}\\b"),
            Regex("\\b\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4}\\b"),
        )
        val isoDateFormatters = listOf(
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(java.time.format.ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(java.time.format.ResolverStyle.STRICT),
        )
        val namedDateFormatters = listOf(
            listOf(
                monthFirstFormatter("MMM"),
                monthFirstFormatter("MMMM"),
            ),
            listOf(
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT),
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT),
            ),
        )
        fun monthFirstFormatter(monthPattern: String): DateTimeFormatter =
            DateTimeFormatterBuilder().parseCaseInsensitive()
                .appendPattern("$monthPattern d")
                .optionalStart().appendLiteral(',').optionalEnd()
                .appendLiteral(' ').appendPattern("uuuu")
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val dateRegexes = listOf(isoDateRegex, numericDateRegex) + namedDateRegexes
    }
}
