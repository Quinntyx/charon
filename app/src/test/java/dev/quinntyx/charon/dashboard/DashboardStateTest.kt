package dev.quinntyx.charon.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DashboardStateTest {
    private val start = LocalDate.of(2025, 6, 1)

    @Test
    fun requestedCurrencyDateAndTransactionAreRetainedWhenPresent() {
        val snapshot = snapshot()

        val state = resolveDashboardState(
            snapshot = snapshot,
            requestedCurrencyCode = "EUR",
            requestedDate = start.plusDays(1),
            requestedTransactionId = "eur-income",
        )

        assertEquals("EUR", state.selectedCurrencyCode)
        assertEquals(start.plusDays(1), state.selectedDate)
        assertEquals("eur-income", state.selectedTransaction?.id)
        assertEquals(listOf("eur-income"), state.recentTransactions.map { it.id })
        assertEquals(listOf("eur-upcoming"), state.upcomingPayments.map { it.id })
    }

    @Test
    fun staleSelectionsFallBackWithoutMixingCurrencies() {
        val state = resolveDashboardState(
            snapshot = snapshot(),
            requestedCurrencyCode = "GBP",
            requestedDate = start.minusDays(1),
            requestedTransactionId = "missing",
        )

        assertEquals("USD", state.selectedCurrencyCode)
        assertEquals(start.plusDays(2), state.selectedDate)
        assertNull(state.selectedTransaction)
        assertEquals(listOf("usd-expense"), state.recentTransactions.map { it.id })
        assertEquals(listOf("usd-upcoming"), state.upcomingPayments.map { it.id })
    }

    @Test
    fun currencyWithOnlyAnUpcomingPaymentIsStillSelectable() {
        val snapshot = DashboardSnapshot(
            period = DashboardPeriod.THIRTY_DAYS,
            currencies = emptyList(),
            upcomingPayments = listOf(
                UpcomingPayment(
                    id = "future-rent",
                    title = "Rent",
                    dueDate = start.plusDays(5),
                    amountMinor = 50_000,
                    currencyCode = "GBP",
                ),
            ),
            recentTransactions = emptyList(),
        )

        val state = resolveDashboardState(snapshot, null, null, null)

        assertEquals(listOf("GBP"), state.currencyCodes)
        assertEquals("GBP", state.selectedCurrencyCode)
        assertEquals(listOf("future-rent"), state.upcomingPayments.map { it.id })
        assertNull(state.selectedOverview)
    }

    @Test
    fun latestZeroSpendDayRemainsSelectable() {
        val state = resolveDashboardState(
            snapshot = snapshot(),
            requestedCurrencyCode = "USD",
            requestedDate = null,
            requestedTransactionId = null,
        )

        val selectedDay = state.selectedOverview?.dailyActivity
            ?.single { it.date == state.selectedDate }
        assertEquals(0L, selectedDay?.expenseMinor)
        assertEquals(start.plusDays(2), selectedDay?.date)
    }

    private fun snapshot(): DashboardSnapshot = DashboardSnapshot(
        period = DashboardPeriod.SEVEN_DAYS,
        currencies = listOf(
            overview(
                currencyCode = "USD",
                expenses = listOf(500L, 200L, 0L),
            ),
            overview(
                currencyCode = "EUR",
                expenses = listOf(100L, 300L, 50L),
            ),
        ),
        upcomingPayments = listOf(
            UpcomingPayment(
                id = "eur-upcoming",
                title = "Rent",
                dueDate = start.plusDays(5),
                amountMinor = 50_000,
                currencyCode = "EUR",
            ),
            UpcomingPayment(
                id = "usd-upcoming",
                title = "Phone",
                dueDate = start.plusDays(4),
                amountMinor = 4_000,
                currencyCode = "USD",
            ),
        ),
        recentTransactions = listOf(
            DashboardTransaction(
                id = "eur-income",
                occurredOn = start,
                merchantName = null,
                folderName = "Current account",
                tagName = "Salary",
                kind = TransactionKind.INCOME,
                amountMinor = 200_000,
                currencyCode = "EUR",
            ),
            DashboardTransaction(
                id = "usd-expense",
                occurredOn = start.plusDays(1),
                merchantName = "Market",
                folderName = "Card",
                tagName = "Groceries",
                kind = TransactionKind.EXPENSE,
                amountMinor = 700,
                currencyCode = "USD",
            ),
        ),
    )

    private fun overview(currencyCode: String, expenses: List<Long>) = CurrencyOverview(
        currencyCode = currencyCode,
        rangeStart = start,
        rangeEnd = start.plusDays(2),
        dailyActivity = expenses.mapIndexed { index, expense ->
            DailyActivity(
                date = start.plusDays(index.toLong()),
                expenseMinor = expense,
                incomeMinor = 0,
            )
        },
        expenseTotalMinor = expenses.sum(),
        incomeTotalMinor = 0,
        rollingExpenseAverageMinor = expenses.sum() / expenses.size,
        rollingAverageWindowDays = expenses.size,
        tagSpending = emptyList(),
    )
}
