package dev.quinntyx.charon.recurrence

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecurrenceAtomicityTest {
    @Test
    fun catchUpStartingAfterCompletedPauseReadsSuppressedState() = runTest {
        val repository = GatedRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(weeklyRequest())
        repository.gateNext(GatedRecurrenceRepository.ReadKind.ONE_PAYMENT)

        val pause = async { service.pause(payment.id, LocalDate.parse("2025-01-08")) }
        repository.awaitGate()
        val catchUp = async { service.catchUp(LocalDate.parse("2025-01-29")) }
        runCurrent()

        assertFalse("catch-up must wait for the schedule mutation transaction", catchUp.isCompleted)
        repository.openGate()
        pause.await()
        catchUp.await()

        assertEquals(
            listOf(LocalDate.parse("2025-01-01")),
            repository.getLoggedOccurrences().map { it.occurrence.key.dueDate },
        )
    }

    @Test
    fun pauseCannotCompleteBetweenCatchUpReadAndLogWrite() = runTest {
        val repository = GatedRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(weeklyRequest())
        repository.gateNext(GatedRecurrenceRepository.ReadKind.ALL_PAYMENTS)

        val catchUp = async { service.catchUp(LocalDate.parse("2025-01-15")) }
        repository.awaitGate()
        val pause = async { service.pause(payment.id, LocalDate.parse("2025-01-08")) }
        runCurrent()

        assertFalse("pause must not commit inside catch-up's transaction", pause.isCompleted)
        repository.openGate()
        catchUp.await()
        pause.await()

        assertEquals(
            listOf("2025-01-15", "2025-01-08", "2025-01-01").map(LocalDate::parse),
            repository.getLoggedOccurrences().map { it.occurrence.key.dueDate },
        )
        assertEquals(
            LocalDate.parse("2025-01-08"),
            repository.getPayment(payment.id)?.pausedSince,
        )
    }

    @Test
    fun concurrentMutationsPreserveBothChanges() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(weeklyRequest())

        val amountChange = async {
            service.changeAmount(payment.id, 4_000, LocalDate.parse("2025-02-01"))
        }
        val pause = async { service.pause(payment.id, LocalDate.parse("2025-01-20")) }
        amountChange.await()
        pause.await()

        val saved = requireNotNull(repository.getPayment(payment.id))
        assertEquals(LocalDate.parse("2025-01-20"), saved.pausedSince)
        assertEquals(
            listOf(2_500L, 4_000L),
            saved.amountRevisions.map { it.minorUnits },
        )
    }

    @Test
    fun failedInMemoryTransactionRollsBackWithoutPublishingPartialState() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(weeklyRequest())

        val failure = runCatching {
            repository.inTransaction<Unit> {
                savePayment(payment.pause(LocalDate.parse("2025-01-08")))
                error("force rollback")
            }
        }

        assertTrue(failure.isFailure)
        assertNull(repository.getPayment(payment.id)?.pausedSince)
    }

    private fun service(repository: RecurrenceRepository) = RecurrenceService(
        repository = repository,
        clock = Clock.fixed(Instant.parse("2025-01-31T12:00:00Z"), ZoneOffset.UTC),
        idGenerator = IdGenerator { "atomic-payment" },
    )

    private fun weeklyRequest() = CreateRecurringPayment(
        title = "Weekly payment",
        amountMinorUnits = 2_500,
        currency = "USD",
        folderId = "checking",
        startsOn = LocalDate.parse("2025-01-01"),
        rule = RecurrenceRule.Weekly(),
        loggingPolicy = LoggingPolicy.AUTOMATIC_CATCH_UP,
    )
}

private class GatedRecurrenceRepository(
    private val delegate: InMemoryRecurrenceRepository = InMemoryRecurrenceRepository(),
) : RecurrenceRepository {
    enum class ReadKind { ONE_PAYMENT, ALL_PAYMENTS }

    private var gatedRead: ReadKind? = null
    private var gateConsumed = false
    private var gateReached = CompletableDeferred<Unit>()
    private var gateRelease = CompletableDeferred<Unit>()

    override fun observePayments(): Flow<List<RecurringPayment>> = delegate.observePayments()

    override fun observeLoggedOccurrences(): Flow<List<LoggedOccurrence>> =
        delegate.observeLoggedOccurrences()

    fun gateNext(kind: ReadKind) {
        gatedRead = kind
        gateConsumed = false
        gateReached = CompletableDeferred()
        gateRelease = CompletableDeferred()
    }

    suspend fun awaitGate() = gateReached.await()

    fun openGate() {
        gateRelease.complete(Unit)
    }

    override suspend fun <T> inTransaction(block: suspend RecurrenceTransaction.() -> T): T =
        delegate.inTransaction {
            val delegateTransaction = this
            val gatedTransaction = object : RecurrenceTransaction {
                override suspend fun getPayment(id: String): RecurringPayment? {
                    val result = delegateTransaction.getPayment(id)
                    awaitIfGated(ReadKind.ONE_PAYMENT)
                    return result
                }

                override suspend fun getPayments(): List<RecurringPayment> {
                    val result = delegateTransaction.getPayments()
                    awaitIfGated(ReadKind.ALL_PAYMENTS)
                    return result
                }

                override suspend fun savePayment(payment: RecurringPayment) =
                    delegateTransaction.savePayment(payment)

                override suspend fun getLoggedOccurrences(): List<LoggedOccurrence> =
                    delegateTransaction.getLoggedOccurrences()

                override suspend fun insertLoggedOccurrenceIfAbsent(
                    occurrence: LoggedOccurrence,
                ): Boolean = delegateTransaction.insertLoggedOccurrenceIfAbsent(occurrence)
            }
            block(gatedTransaction)
        }

    private suspend fun awaitIfGated(kind: ReadKind) {
        if (gatedRead == kind && !gateConsumed) {
            gateConsumed = true
            gateReached.complete(Unit)
            gateRelease.await()
        }
    }
}
