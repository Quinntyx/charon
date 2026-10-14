package dev.quinntyx.charon.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A normalized three-letter currency key. Amounts in different currencies are never combined. */
@JvmInline
value class CurrencyCode(val value: String) : Comparable<CurrencyCode> {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) {
            "Currency codes must contain exactly three uppercase ASCII letters"
        }
    }

    override fun compareTo(other: CurrencyCode): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        fun of(rawValue: String): CurrencyCode {
            val normalized = rawValue.trim().uppercase(Locale.ROOT)
            require(normalized.length == 3 && normalized.all { it in 'A'..'Z' }) {
                "Currency codes must contain exactly three ASCII letters"
            }
            return CurrencyCode(normalized)
        }
    }
}

enum class AnalyticsTransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
}

/**
 * Persistence-independent transaction projection consumed by analytics.
 *
 * [amountMinor] is always non-negative; direction is represented by [type]. [transactionId] must
 * remain stable across rows so a transaction repeated by a tag join can be counted only once.
 */
data class AnalyticsTransaction(
    val transactionId: Long,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: CurrencyCode,
    val type: AnalyticsTransactionType,
    val folderId: Long?,
    val tagIds: Set<Long> = emptySet(),
) {
    init {
        require(amountMinor >= 0L) { "Transaction amounts must use non-negative minor units" }
    }
}

enum class TagMatch {
    /** A transaction matches when it has at least one selected tag. */
    ANY,

    /** A transaction matches only when it has every selected tag. */
    ALL,
}

/** Empty filter sets mean "all". Folder and tag constraints are combined with AND. */
data class AnalyticsFilter(
    val folderIds: Set<Long> = emptySet(),
    val tagIds: Set<Long> = emptySet(),
    val tagMatch: TagMatch = TagMatch.ANY,
    val currencies: Set<CurrencyCode> = emptySet(),
)

data class DateRange(
    val startInclusive: LocalDate,
    val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(startInclusive)) { "Range end must not precede its start" }
        require(ChronoUnit.DAYS.between(startInclusive, endInclusive) < Int.MAX_VALUE.toLong()) {
            "Date range is too large to materialize"
        }
    }

    val dayCount: Int
        get() = Math.toIntExact(ChronoUnit.DAYS.between(startInclusive, endInclusive) + 1L)

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(startInclusive) && !date.isAfter(endInclusive)

    fun previousEqualLength(): DateRange = DateRange(
        startInclusive = startInclusive.minusDays(dayCount.toLong()),
        endInclusive = startInclusive.minusDays(1L),
    )
}

data class DailyExpense(
    val date: LocalDate,
    val amountMinor: Long,
)

data class CurrencyExpenseSeries(
    val currency: CurrencyCode,
    val range: DateRange,
    val points: List<DailyExpense>,
)

/** Exact average represented without floating-point money. */
data class DailyAverage(
    val totalMinor: Long,
    val dayCount: Int,
) {
    init {
        require(totalMinor >= 0L) { "Expense totals cannot be negative" }
        require(dayCount > 0) { "Average window must include at least one day" }
    }

    val wholeMinorUnitsPerDay: Long
        get() = totalMinor / dayCount

    val remainderMinorUnits: Long
        get() = totalMinor % dayCount
}

data class RollingAverage(
    val currency: CurrencyCode,
    val windowDays: Int,
    val range: DateRange,
    val average: DailyAverage,
)

data class CurrencyTotals(
    val amounts: Map<CurrencyCode, Long>,
) {
    operator fun get(currency: CurrencyCode): Long = amounts[currency] ?: 0L
}

/**
 * Overall totals count each transaction once. A multi-tag transaction appears in each applicable
 * tag bucket, so tag buckets are intentionally not additive and must not be used as an overall sum.
 */
data class ExpenseSummary(
    val range: DateRange,
    val overall: CurrencyTotals,
    val byFolder: Map<Long, CurrencyTotals>,
    val byTag: Map<Long, CurrencyTotals>,
)

data class ExpensePeriodComparison(
    val currency: CurrencyCode,
    val currentRange: DateRange,
    val previousRange: DateRange,
    val current: DailyAverage,
    val previous: DailyAverage,
    val differenceMinor: Long,
)
