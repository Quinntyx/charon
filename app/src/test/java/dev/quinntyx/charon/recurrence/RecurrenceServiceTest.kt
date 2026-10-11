package dev.quinntyx.charon.recurrence

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2025-04-10T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun automaticCatchUpIsIdempotentAndManualPolicyStaysPending() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val automatic = service.create(request("automatic", LoggingPolicy.AUTOMATIC_CATCH_UP))
        val manual = service.create(request("manual", LoggingPolicy.MANUAL_CONFIRMATION))

        val first = service.catchUp(LocalDate.parse("2025-04-10"))
        val second = service.catchUp(LocalDate.parse("2025-04-10"))

        assertEquals(CatchUpResult(2, 2), first)
        assertEquals(CatchUpResult(2, 0), second)
        val logs = repository.getLoggedOccurrences()
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.occurrence.key.recurringPaymentId == automatic.id })
        assertTrue(logs.all { it.origin == LoggingOrigin.AUTOMATIC_CATCH_UP })
        val pending = service.pendingOccurrences(
            LocalDate.parse("2025-03-01"),
            LocalDate.parse("2025-04-10"),
        )
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.key.recurringPaymentId == manual.id })
    }

    @Test
    fun concurrentCatchUpStillInsertsEachOccurrenceOnce() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        service.create(request("rent", LoggingPolicy.AUTOMATIC_CATCH_UP))

        List(8) { async { service.catchUp(LocalDate.parse("2025-04-10")) } }.awaitAll()

        assertEquals(2, repository.getLoggedOccurrences().size)
    }

    @Test
    fun pauseResumeSkipAndCancelSuppressOnlyIntendedOccurrences() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(
            request("weekly", LoggingPolicy.AUTOMATIC_CATCH_UP).copy(
                startsOn = LocalDate.parse("2025-01-01"),
                rule = RecurrenceRule.Weekly(),
            ),
        )

        service.pause(payment.id, LocalDate.parse("2025-01-08"))
        service.resume(payment.id, LocalDate.parse("2025-01-22"))
        service.skip(payment.id, LocalDate.parse("2025-01-29"))
        service.cancel(payment.id, LocalDate.parse("2025-02-12"))

        val dates = service.pendingOccurrences(
            LocalDate.parse("2025-01-01"),
            LocalDate.parse("2025-02-28"),
        ).map { it.key.dueDate }
        assertEquals(
            listOf("2025-01-01", "2025-01-22", "2025-02-05").map(LocalDate::parse),
            dates,
        )
    }

    @Test
    fun amountChangesAffectFutureSnapshotsButNotExistingLogs() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(
            request("subscription", LoggingPolicy.MANUAL_CONFIRMATION).copy(
                startsOn = LocalDate.parse("2025-01-31"),
                rule = RecurrenceRule.Monthly(),
                amountMinorUnits = 1_000,
            ),
        )
        service.logManually(
            payment.id,
            dueDate = LocalDate.parse("2025-01-31"),
            loggedOn = LocalDate.parse("2025-01-31"),
        )
        service.changeAmount(payment.id, 1_250, LocalDate.parse("2025-03-01"))

        val future = service.pendingOccurrences(
            LocalDate.parse("2025-02-01"),
            LocalDate.parse("2025-03-31"),
        )
        assertEquals(listOf(1_000L, 1_250L), future.map { it.amountMinorUnits })
        assertEquals(1_000L, repository.getLoggedOccurrences().single().occurrence.amountMinorUnits)
    }

    @Test
    fun manualLoggingMovesOccurrenceOutOfPendingAndRejectsEarlyLogging() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(request("manual", LoggingPolicy.MANUAL_CONFIRMATION))
        val dueDate = LocalDate.parse("2025-04-01")

        assertTrue(service.logManually(payment.id, dueDate, loggedOn = LocalDate.parse("2025-04-01")))
        assertFalse(service.logManually(payment.id, dueDate, loggedOn = LocalDate.parse("2025-04-01")))
        assertTrue(service.pendingOccurrences(dueDate, dueDate).isEmpty())

        val futureFailure = runCatching {
            service.logManually(
                payment.id,
                LocalDate.parse("2025-05-01"),
                loggedOn = LocalDate.parse("2025-04-10"),
            )
        }
        assertTrue(futureFailure.isFailure)
    }

    @Test
    fun loggedSnapshotKeepsFolderMerchantTagsCurrencyAndIntegerAmount() = runTest {
        val repository = InMemoryRecurrenceRepository()
        val service = service(repository)
        val payment = service.create(
            request("utility", LoggingPolicy.MANUAL_CONFIRMATION).copy(
                folderId = "checking",
                merchantId = "power-company",
                tagIds = setOf("utilities"),
                currency = "eur",
                amountMinorUnits = 9_999,
            ),
        )

        service.logManually(
            payment.id,
            LocalDate.parse("2025-04-01"),
            loggedOn = LocalDate.parse("2025-04-01"),
        )
        val logged = repository.getLoggedOccurrences().single().occurrence
        assertEquals("checking", logged.folderId)
        assertEquals("power-company", logged.merchantId)
        assertEquals(setOf("utilities"), logged.tagIds)
        assertEquals("EUR", logged.currency)
        assertEquals(9_999L, logged.amountMinorUnits)
    }

    private fun service(repository: RecurrenceRepository) = RecurrenceService(
        repository = repository,
        clock = fixedClock,
        idGenerator = object : IdGenerator {
            private var next = 0
            override fun newId(): String = "payment-${next++}"
        },
    )

    private fun request(title: String, policy: LoggingPolicy) = CreateRecurringPayment(
        title = title,
        amountMinorUnits = 2_500,
        currency = "USD",
        folderId = "checking",
        startsOn = LocalDate.parse("2025-03-01"),
        rule = RecurrenceRule.Monthly(),
        loggingPolicy = policy,
    )
}
