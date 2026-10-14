package dev.quinntyx.charon.recurrence

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/** A calendar rule anchored to [RecurringPayment.startsOn]. */
sealed interface RecurrenceRule {
    data class Weekly(val everyWeeks: Int = 1) : RecurrenceRule {
        init {
            require(everyWeeks > 0) { "everyWeeks must be positive" }
        }
    }

    data class Monthly(val everyMonths: Int = 1) : RecurrenceRule {
        init {
            require(everyMonths > 0) { "everyMonths must be positive" }
        }
    }

    data class Yearly(val everyYears: Int = 1) : RecurrenceRule {
        init {
            require(everyYears > 0) { "everyYears must be positive" }
        }
    }

    /** A fixed number of calendar days. This is not an exact elapsed-time alarm. */
    data class CustomDays(val everyDays: Int) : RecurrenceRule {
        init {
            require(everyDays > 0) { "everyDays must be positive" }
        }
    }
}

enum class LoggingPolicy {
    /** The user must explicitly turn a due occurrence into a logged transaction. */
    MANUAL_CONFIRMATION,

    /** A best-effort background catch-up logs due occurrences on or after their date. */
    AUTOMATIC_CATCH_UP,
}

enum class PaymentStatus { ACTIVE, PAUSED, CANCELLED }

enum class LoggingOrigin { MANUAL_CONFIRMATION, AUTOMATIC_CATCH_UP }

data class PauseWindow(val from: LocalDate, val untilExclusive: LocalDate) {
    init {
        require(untilExclusive >= from) { "Pause end cannot precede its start" }
    }

    operator fun contains(date: LocalDate): Boolean = date >= from && date < untilExclusive
}

data class AmountRevision(
    val effectiveFrom: LocalDate,
    val minorUnits: Long,
) {
    init {
        require(minorUnits > 0) { "Recurring payment amount must be positive minor units" }
    }
}

/**
 * An expense template. Money is always represented as integer minor units and currency is fixed for
 * the life of a payment. A changed amount only affects occurrences on/after its effective date.
 */
data class RecurringPayment(
    val id: String,
    val title: String,
    val currency: String,
    val folderId: String,
    val merchantId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val startsOn: LocalDate,
    val rule: RecurrenceRule,
    val loggingPolicy: LoggingPolicy,
    val amountRevisions: List<AmountRevision>,
    val skippedDates: Set<LocalDate> = emptySet(),
    val pauseWindows: List<PauseWindow> = emptyList(),
    val pausedSince: LocalDate? = null,
    val cancelledFrom: LocalDate? = null,
) {
    init {
        require(id.isNotBlank()) { "Payment id cannot be blank" }
        require(title.isNotBlank()) { "Payment title cannot be blank" }
        require(folderId.isNotBlank()) { "A source folder is required" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "Currency must be a three-letter uppercase code" }
        require(amountRevisions.isNotEmpty()) { "At least one amount is required" }
        require(amountRevisions == amountRevisions.sortedBy { it.effectiveFrom }) {
            "Amount revisions must be sorted"
        }
        require(amountRevisions.map { it.effectiveFrom }.distinct().size == amountRevisions.size) {
            "Amount revisions must have unique effective dates"
        }
        require(amountRevisions.first().effectiveFrom == startsOn) {
            "The first amount must take effect on the start date"
        }
        require(amountRevisions.all { it.effectiveFrom >= startsOn }) {
            "Amount revisions cannot precede the start date"
        }
        require(skippedDates.all { it >= startsOn }) { "Skipped dates cannot precede the start date" }
        require(pauseWindows.all { it.from >= startsOn }) { "Pause windows cannot precede the start date" }
        require(pausedSince == null || pausedSince >= startsOn) { "Pause cannot precede the start date" }
        require(cancelledFrom == null || cancelledFrom >= startsOn) {
            "Cancellation cannot precede the start date"
        }
    }

    fun status(onDate: LocalDate): PaymentStatus = when {
        cancelledFrom != null && onDate >= cancelledFrom -> PaymentStatus.CANCELLED
        pausedSince != null && onDate >= pausedSince -> PaymentStatus.PAUSED
        else -> PaymentStatus.ACTIVE
    }

    fun amountMinorUnitsOn(dueDate: LocalDate): Long =
        amountRevisions.last { it.effectiveFrom <= dueDate }.minorUnits

    fun isSuppressed(dueDate: LocalDate): Boolean =
        dueDate in skippedDates ||
            pauseWindows.any { dueDate in it } ||
            (pausedSince != null && dueDate >= pausedSince) ||
            (cancelledFrom != null && dueDate >= cancelledFrom)

    fun pause(from: LocalDate): RecurringPayment {
        require(pausedSince == null) { "Payment is already paused" }
        require(cancelledFrom == null || from < cancelledFrom) { "Cancelled payment cannot be paused" }
        require(from >= startsOn) { "Pause cannot precede start date" }
        return copy(pausedSince = from)
    }

    /** Occurrences in [pausedSince, resumeOn) remain suppressed permanently. */
    fun resume(resumeOn: LocalDate): RecurringPayment {
        val pauseStart = requireNotNull(pausedSince) { "Payment is not paused" }
        require(resumeOn >= pauseStart) { "Resume cannot precede pause" }
        val completedWindow = PauseWindow(pauseStart, resumeOn)
        return copy(
            pauseWindows = if (resumeOn == pauseStart) pauseWindows else pauseWindows + completedWindow,
            pausedSince = null,
        )
    }

    fun cancel(from: LocalDate): RecurringPayment {
        require(cancelledFrom == null) { "Payment is already cancelled" }
        require(from >= startsOn) { "Cancellation cannot precede start date" }
        return copy(cancelledFrom = from)
    }

    fun skip(dueDate: LocalDate): RecurringPayment = copy(skippedDates = skippedDates + dueDate)

    fun changeAmount(minorUnits: Long, effectiveFrom: LocalDate): RecurringPayment {
        require(effectiveFrom >= startsOn) { "Amount change cannot precede start date" }
        val replacement = AmountRevision(effectiveFrom, minorUnits)
        val revised = (amountRevisions.filterNot { it.effectiveFrom == effectiveFrom } + replacement)
            .sortedBy { it.effectiveFrom }
        return copy(amountRevisions = revised)
    }
}

data class OccurrenceKey(val recurringPaymentId: String, val dueDate: LocalDate)

data class ScheduledOccurrence(
    val key: OccurrenceKey,
    val title: String,
    val currency: String,
    val amountMinorUnits: Long,
    val folderId: String,
    val merchantId: String?,
    val tagIds: Set<String>,
    val loggingPolicy: LoggingPolicy,
)

data class LoggedOccurrence(
    val occurrence: ScheduledOccurrence,
    val loggedAtEpochMillis: Long,
    val origin: LoggingOrigin,
)

/**
 * Returns anchored dates in the inclusive range. Monthly and yearly rules clamp invalid days to the
 * target month's end without losing the original anchor (Jan 31 -> Feb 28 -> Mar 31; Feb 29 returns
 * on the next leap year).
 */
fun occurrenceDatesBetween(
    rule: RecurrenceRule,
    startsOn: LocalDate,
    fromInclusive: LocalDate,
    toInclusive: LocalDate,
): List<LocalDate> {
    if (toInclusive < fromInclusive || toInclusive < startsOn) return emptyList()
    val result = mutableListOf<LocalDate>()
    var index = 0L
    while (index < MAX_OCCURRENCES) {
        val date = occurrenceAt(rule, startsOn, index) ?: break
        if (date > toInclusive) break
        if (date >= fromInclusive) result += date
        index++
    }
    check(index < MAX_OCCURRENCES) { "Occurrence range is too large" }
    return result
}

fun nextOccurrenceOnOrAfter(
    rule: RecurrenceRule,
    startsOn: LocalDate,
    date: LocalDate,
): LocalDate? {
    var index = 0L
    while (index < MAX_OCCURRENCES) {
        val candidate = occurrenceAt(rule, startsOn, index) ?: return null
        if (candidate >= date) return candidate
        index++
    }
    error("Occurrence range is too large")
}

private fun occurrenceAt(rule: RecurrenceRule, startsOn: LocalDate, index: Long): LocalDate? =
    try {
        when (rule) {
            is RecurrenceRule.Weekly -> startsOn.plusWeeks(Math.multiplyExact(index, rule.everyWeeks.toLong()))
            is RecurrenceRule.CustomDays -> startsOn.plusDays(Math.multiplyExact(index, rule.everyDays.toLong()))
            is RecurrenceRule.Monthly -> {
                val target = YearMonth.from(startsOn)
                    .plusMonths(Math.multiplyExact(index, rule.everyMonths.toLong()))
                target.atDay(minOf(startsOn.dayOfMonth, target.lengthOfMonth()))
            }
            is RecurrenceRule.Yearly -> {
                val yearOffset = Math.multiplyExact(index, rule.everyYears.toLong())
                val targetYear = Math.toIntExact(Math.addExact(startsOn.year.toLong(), yearOffset))
                val target = YearMonth.of(targetYear, startsOn.month)
                target.atDay(minOf(startsOn.dayOfMonth, target.lengthOfMonth()))
            }
        }
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

private const val MAX_OCCURRENCES = 1_000_000L
