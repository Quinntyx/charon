package dev.quinntyx.charon.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.quinntyx.charon.dashboard.DashboardPeriod
import dev.quinntyx.charon.dashboard.RoomDashboardRepository
import dev.quinntyx.charon.domain.DeleteResult
import dev.quinntyx.charon.domain.NewTransaction
import dev.quinntyx.charon.recurrence.CreateRecurringPayment
import dev.quinntyx.charon.recurrence.LoggingPolicy
import dev.quinntyx.charon.recurrence.RecurrenceRule
import dev.quinntyx.charon.recurrence.RecurrenceService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomAppIntegrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: CharonDatabase
    private lateinit var ledger: RoomLedgerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "app-integration-${UUID.randomUUID()}.db"
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun recurrenceLogsIntoSharedLedgerExactlyOnceAndSurvivesRestart() = runTest {
        val clock = Clock.fixed(Instant.parse("2025-02-15T12:00:00Z"), ZoneOffset.UTC)
        val wallet = ledger.createAccount("Wallet", "USD")
        val recurrence = RecurrenceService(RoomRecurrenceRepository(database, ZoneOffset.UTC), clock)
        val payment = recurrence.create(
            CreateRecurringPayment(
                title = "Rent",
                amountMinorUnits = 125_000,
                currency = "USD",
                folderId = wallet.id.toString(),
                startsOn = LocalDate.of(2025, 2, 1),
                rule = RecurrenceRule.Monthly(),
                loggingPolicy = LoggingPolicy.MANUAL_CONFIRMATION,
            ),
        )

        assertTrue(recurrence.logManually(payment.id, LocalDate.of(2025, 2, 1)))
        assertFalse(recurrence.logManually(payment.id, LocalDate.of(2025, 2, 1)))
        assertEquals(-125_000L, ledger.getAccountBalance(wallet.id))

        val dashboard = RoomDashboardRepository(ledger, recurrence, clock)
            .observeDashboard(DashboardPeriod.THIRTY_DAYS)
            .first()
        assertEquals(1, dashboard.recentTransactions.size)
        assertEquals(125_000L, dashboard.currencies.single().expenseTotalMinor)
        assertTrue(dashboard.upcomingPayments.none { it.dueDate == LocalDate.of(2025, 2, 1) })

        database.close()
        openDatabase()
        val reopenedRecurrence = RecurrenceService(RoomRecurrenceRepository(database, ZoneOffset.UTC), clock)
        assertEquals(1, ledger.getTransactions().size)
        assertEquals(1, reopenedRecurrence.repository.observeLoggedOccurrences().first().size)
        assertFalse(reopenedRecurrence.logManually(payment.id, LocalDate.of(2025, 2, 1)))
        assertEquals(1, ledger.getTransactions().size)
    }

    @Test
    fun balancesAndSafeDeletionRespectIncomeExpenseTransferAndReferences() = runTest {
        val checking = ledger.createAccount("Checking", "USD")
        val savings = ledger.createAccount("Savings", "USD")
        val unused = ledger.createAccount("Unused", "USD")
        val tag = ledger.createTag("Essential")
        val unusedTag = ledger.createTag("Unused")

        ledger.record(NewTransaction.Income(10_000, "USD", 1, checking.id))
        ledger.record(NewTransaction.Expense(2_500, "USD", 2, checking.id, tagIds = setOf(tag.id)))
        ledger.record(NewTransaction.Transfer(3_000, "USD", 3, checking.id, savings.id))

        assertEquals(4_500L, ledger.getAccountBalance(checking.id))
        assertEquals(3_000L, ledger.getAccountBalance(savings.id))
        assertTrue(ledger.deleteAccountSafely(checking.id) is DeleteResult.Blocked)
        assertEquals(DeleteResult.Deleted, ledger.deleteAccountSafely(unused.id))
        assertTrue(ledger.deleteTagSafely(tag.id) is DeleteResult.Blocked)
        assertEquals(DeleteResult.Deleted, ledger.deleteTagSafely(unusedTag.id))
    }

    private fun openDatabase() {
        database = CharonDatabase.open(context, databaseName)
        ledger = RoomLedgerRepository(database, System::currentTimeMillis)
    }
}
