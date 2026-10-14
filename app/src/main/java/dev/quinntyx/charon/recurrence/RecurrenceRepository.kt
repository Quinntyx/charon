package dev.quinntyx.charon.recurrence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** Operations available inside one atomic recurrence transaction. */
interface RecurrenceTransaction {
    suspend fun getPayment(id: String): RecurringPayment?
    suspend fun getPayments(): List<RecurringPayment>
    suspend fun savePayment(payment: RecurringPayment)
    suspend fun getLoggedOccurrences(): List<LoggedOccurrence>

    /** Returns true only when this transaction inserted the occurrence. */
    suspend fun insertLoggedOccurrenceIfAbsent(occurrence: LoggedOccurrence): Boolean
}

/**
 * Persistence boundary for the recurrence subsystem.
 *
 * A Room implementation must run [inTransaction] with `RoomDatabase.withTransaction` and enforce a
 * unique key on `(recurringPaymentId, dueDate)`. All payment validation and the corresponding write
 * then share one serialization boundary: catch-up cannot log from stale schedule state, and two
 * mutations cannot overwrite one another.
 */
interface RecurrenceRepository {
    fun observePayments(): Flow<List<RecurringPayment>>
    fun observeLoggedOccurrences(): Flow<List<LoggedOccurrence>>

    suspend fun <T> inTransaction(block: suspend RecurrenceTransaction.() -> T): T

    suspend fun getPayment(id: String): RecurringPayment? = inTransaction { getPayment(id) }

    suspend fun getPayments(): List<RecurringPayment> = inTransaction { getPayments() }

    suspend fun savePayment(payment: RecurringPayment) = inTransaction { savePayment(payment) }

    suspend fun getLoggedOccurrences(): List<LoggedOccurrence> =
        inTransaction { getLoggedOccurrences() }

    suspend fun insertLoggedOccurrenceIfAbsent(occurrence: LoggedOccurrence): Boolean =
        inTransaction { insertLoggedOccurrenceIfAbsent(occurrence) }
}

/** Useful for previews, tests, and temporary host integration; production should bind the Room store. */
class InMemoryRecurrenceRepository : RecurrenceRepository {
    private val mutex = Mutex()
    private val payments = MutableStateFlow<List<RecurringPayment>>(emptyList())
    private val logs = MutableStateFlow<List<LoggedOccurrence>>(emptyList())

    override fun observePayments(): Flow<List<RecurringPayment>> = payments

    override fun observeLoggedOccurrences(): Flow<List<LoggedOccurrence>> = logs

    /**
     * Stages both collections and publishes them only after a successful block. This mirrors Room
     * rollback behavior and prevents observers from seeing a partially completed catch-up.
     */
    override suspend fun <T> inTransaction(block: suspend RecurrenceTransaction.() -> T): T {
        mutex.lock()
        try {
            var stagedPayments = payments.value
            var stagedLogs = logs.value
            val transaction = object : RecurrenceTransaction {
                override suspend fun getPayment(id: String): RecurringPayment? =
                    stagedPayments.firstOrNull { it.id == id }

                override suspend fun getPayments(): List<RecurringPayment> = stagedPayments

                override suspend fun savePayment(payment: RecurringPayment) {
                    stagedPayments = (stagedPayments.filterNot { it.id == payment.id } + payment)
                        .sortedBy { it.title.lowercase() }
                }

                override suspend fun getLoggedOccurrences(): List<LoggedOccurrence> = stagedLogs

                override suspend fun insertLoggedOccurrenceIfAbsent(
                    occurrence: LoggedOccurrence,
                ): Boolean {
                    val key = occurrence.occurrence.key
                    if (stagedLogs.any { it.occurrence.key == key }) return false
                    stagedLogs = (stagedLogs + occurrence)
                        .sortedWith(
                            compareByDescending<LoggedOccurrence> { it.occurrence.key.dueDate }
                                .thenBy { it.occurrence.title },
                        )
                    return true
                }
            }
            val result = block(transaction)
            if (stagedPayments != payments.value) payments.value = stagedPayments
            if (stagedLogs != logs.value) logs.value = stagedLogs
            return result
        } finally {
            mutex.unlock()
        }
    }
}
