package dev.quinntyx.charon.recurrence

import java.time.Clock
import java.time.LocalDate
import java.util.UUID

fun interface IdGenerator {
    fun newId(): String
}

data class CreateRecurringPayment(
    val title: String,
    val amountMinorUnits: Long,
    val currency: String,
    val folderId: String,
    val startsOn: LocalDate,
    val rule: RecurrenceRule,
    val loggingPolicy: LoggingPolicy,
    val merchantId: String? = null,
    val tagIds: Set<String> = emptySet(),
)

data class CatchUpResult(
    val eligibleOccurrences: Int,
    val newlyLoggedOccurrences: Int,
)

class RecurrenceService(
    val repository: RecurrenceRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idGenerator: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
) {
    suspend fun create(request: CreateRecurringPayment): RecurringPayment {
        val payment = RecurringPayment(
            id = idGenerator.newId(),
            title = request.title.trim(),
            currency = request.currency.trim().uppercase(),
            folderId = request.folderId.trim(),
            merchantId = request.merchantId?.trim()?.takeIf { it.isNotEmpty() },
            tagIds = request.tagIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            startsOn = request.startsOn,
            rule = request.rule,
            loggingPolicy = request.loggingPolicy,
            amountRevisions = listOf(AmountRevision(request.startsOn, request.amountMinorUnits)),
        )
        repository.inTransaction { savePayment(payment) }
        return payment
    }

    suspend fun pause(paymentId: String, from: LocalDate = today()) = mutate(paymentId) { it.pause(from) }

    suspend fun resume(paymentId: String, resumeOn: LocalDate = today()) =
        mutate(paymentId) { it.resume(resumeOn) }

    suspend fun cancel(paymentId: String, from: LocalDate = today()) = mutate(paymentId) { it.cancel(from) }

    suspend fun skip(paymentId: String, dueDate: LocalDate) = mutate(paymentId) { payment ->
        require(isOccurrenceDate(payment, dueDate)) { "$dueDate is not an occurrence of ${payment.title}" }
        require(!payment.isSuppressed(dueDate)) { "$dueDate is already suppressed" }
        payment.skip(dueDate)
    }

    /** The revision applies to the first occurrence on or after [effectiveFrom]. */
    suspend fun changeAmount(paymentId: String, amountMinorUnits: Long, effectiveFrom: LocalDate) =
        mutate(paymentId) { it.changeAmount(amountMinorUnits, effectiveFrom) }

    suspend fun pendingOccurrences(
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
    ): List<ScheduledOccurrence> = repository.inTransaction {
        val loggedKeys = getLoggedOccurrences().mapTo(mutableSetOf()) { it.occurrence.key }
        getPayments()
            .flatMap { payment -> scheduledOccurrences(payment, fromInclusive, toInclusive) }
            .filterNot { it.key in loggedKeys }
            .sortedWith(compareBy<ScheduledOccurrence> { it.key.dueDate }.thenBy { it.title })
    }

    /**
     * Explicitly logs one due occurrence. Future occurrences cannot be logged early. The repository's
     * unique occurrence key makes repeated taps safe.
     */
    suspend fun logManually(
        paymentId: String,
        dueDate: LocalDate,
        loggedAtEpochMillis: Long = clock.millis(),
        loggedOn: LocalDate = today(),
    ): Boolean {
        require(dueDate <= loggedOn) { "Future occurrences cannot be logged early" }
        return repository.inTransaction {
            val payment = requireNotNull(getPayment(paymentId)) {
                "Unknown recurring payment: $paymentId"
            }
            require(isOccurrenceDate(payment, dueDate)) {
                "$dueDate is not an occurrence of ${payment.title}"
            }
            require(!payment.isSuppressed(dueDate)) { "$dueDate is suppressed" }
            insertLoggedOccurrenceIfAbsent(
                LoggedOccurrence(
                    occurrence = payment.toOccurrence(dueDate),
                    loggedAtEpochMillis = loggedAtEpochMillis,
                    origin = LoggingOrigin.MANUAL_CONFIRMATION,
                ),
            )
        }
    }

    /**
     * Logs every eligible automatic occurrence through [asOf]. This is catch-up, not an exact-time
     * promise: WorkManager may run after the due date. Each occurrence is inserted atomically once.
     */
    suspend fun catchUp(asOf: LocalDate = today()): CatchUpResult = repository.inTransaction {
        var eligible = 0
        var inserted = 0
        getPayments()
            .asSequence()
            .filter { it.loggingPolicy == LoggingPolicy.AUTOMATIC_CATCH_UP }
            .forEach { payment ->
                scheduledOccurrences(payment, payment.startsOn, asOf).forEach { occurrence ->
                    eligible++
                    val wasInserted = insertLoggedOccurrenceIfAbsent(
                        LoggedOccurrence(
                            occurrence = occurrence,
                            loggedAtEpochMillis = clock.millis(),
                            origin = LoggingOrigin.AUTOMATIC_CATCH_UP,
                        ),
                    )
                    if (wasInserted) inserted++
                }
            }
        CatchUpResult(eligible, inserted)
    }

    fun scheduledOccurrences(
        payment: RecurringPayment,
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
    ): List<ScheduledOccurrence> = occurrenceDatesBetween(
        rule = payment.rule,
        startsOn = payment.startsOn,
        fromInclusive = fromInclusive,
        toInclusive = toInclusive,
    ).asSequence()
        .filterNot(payment::isSuppressed)
        .map(payment::toOccurrence)
        .toList()

    fun nextUnsuppressedOccurrence(
        payment: RecurringPayment,
        onOrAfter: LocalDate = today(),
        searchThrough: LocalDate = onOrAfter.plusYears(20),
    ): ScheduledOccurrence? = scheduledOccurrences(payment, onOrAfter, searchThrough).firstOrNull()

    fun today(): LocalDate = LocalDate.now(clock)

    private suspend fun mutate(id: String, transform: (RecurringPayment) -> RecurringPayment) {
        repository.inTransaction {
            val current = requireNotNull(getPayment(id)) { "Unknown recurring payment: $id" }
            savePayment(transform(current))
        }
    }

    private fun isOccurrenceDate(payment: RecurringPayment, date: LocalDate): Boolean =
        occurrenceDatesBetween(payment.rule, payment.startsOn, date, date).singleOrNull() == date
}

private fun RecurringPayment.toOccurrence(dueDate: LocalDate): ScheduledOccurrence = ScheduledOccurrence(
    key = OccurrenceKey(id, dueDate),
    title = title,
    currency = currency,
    amountMinorUnits = amountMinorUnitsOn(dueDate),
    folderId = folderId,
    merchantId = merchantId,
    tagIds = tagIds,
    loggingPolicy = loggingPolicy,
)
