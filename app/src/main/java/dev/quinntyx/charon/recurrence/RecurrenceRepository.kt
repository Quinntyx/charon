package dev.quinntyx.charon.recurrence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence boundary for the recurrence subsystem. A Room implementation must enforce a unique
 * key on (recurringPaymentId, dueDate) for [insertLoggedOccurrenceIfAbsent].
 */
interface RecurrenceRepository {
    fun observePayments(): Flow<List<RecurringPayment>>
    fun observeLoggedOccurrences(): Flow<List<LoggedOccurrence>>

    suspend fun getPayment(id: String): RecurringPayment?
    suspend fun getPayments(): List<RecurringPayment>
    suspend fun savePayment(payment: RecurringPayment)
    suspend fun getLoggedOccurrences(): List<LoggedOccurrence>

    /** Returns true only when this call inserted the occurrence. This operation must be atomic. */
    suspend fun insertLoggedOccurrenceIfAbsent(occurrence: LoggedOccurrence): Boolean
}

/** Useful for previews, tests, and temporary host integration; production should bind the Room store. */
class InMemoryRecurrenceRepository : RecurrenceRepository {
    private val mutex = Mutex()
    private val payments = MutableStateFlow<List<RecurringPayment>>(emptyList())
    private val logs = MutableStateFlow<List<LoggedOccurrence>>(emptyList())

    override fun observePayments(): Flow<List<RecurringPayment>> = payments

    override fun observeLoggedOccurrences(): Flow<List<LoggedOccurrence>> = logs

    override suspend fun getPayment(id: String): RecurringPayment? = mutex.withLock {
        payments.value.firstOrNull { it.id == id }
    }

    override suspend fun getPayments(): List<RecurringPayment> = mutex.withLock { payments.value }

    override suspend fun savePayment(payment: RecurringPayment) {
        mutex.withLock {
            payments.value = (payments.value.filterNot { it.id == payment.id } + payment)
                .sortedBy { it.title.lowercase() }
        }
    }

    override suspend fun getLoggedOccurrences(): List<LoggedOccurrence> = mutex.withLock { logs.value }

    override suspend fun insertLoggedOccurrenceIfAbsent(occurrence: LoggedOccurrence): Boolean =
        mutex.withLock {
            val key = occurrence.occurrence.key
            if (logs.value.any { it.occurrence.key == key }) {
                false
            } else {
                logs.value = (logs.value + occurrence)
                    .sortedWith(compareByDescending<LoggedOccurrence> { it.occurrence.key.dueDate }
                        .thenBy { it.occurrence.title })
                true
            }
        }
}
