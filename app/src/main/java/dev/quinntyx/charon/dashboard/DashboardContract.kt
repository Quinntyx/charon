package dev.quinntyx.charon.dashboard

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Read-only boundary between the dashboard and the persistence/analytics subsystems.
 *
 * Implementations are responsible for producing currency-safe aggregates. In particular,
 * [CurrencyOverview.expenseTotalMinor] and daily/tag spending must exclude transfers, and rolling
 * averages must include every calendar day in their stated window, including zero-spend days.
 */
interface DashboardRepository {
    fun observeDashboard(period: DashboardPeriod): Flow<DashboardSnapshot>
}

enum class DashboardPeriod(val dayCount: Int, val label: String) {
    SEVEN_DAYS(7, "7 days"),
    THIRTY_DAYS(30, "30 days"),
    NINETY_DAYS(90, "90 days"),
}

enum class TransactionKind {
    EXPENSE,
    INCOME,
    TRANSFER,
}

data class DashboardSnapshot(
    val period: DashboardPeriod,
    val currencies: List<CurrencyOverview>,
    val upcomingPayments: List<UpcomingPayment>,
    val recentTransactions: List<DashboardTransaction>,
) {
    companion object {
        fun empty(period: DashboardPeriod) = DashboardSnapshot(
            period = period,
            currencies = emptyList(),
            upcomingPayments = emptyList(),
            recentTransactions = emptyList(),
        )
    }
}

data class CurrencyOverview(
    val currencyCode: String,
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val dailyActivity: List<DailyActivity>,
    val expenseTotalMinor: Long,
    val incomeTotalMinor: Long,
    val rollingExpenseAverageMinor: Long,
    val rollingAverageWindowDays: Int,
    val tagSpending: List<TagSpending>,
)

data class DailyActivity(
    val date: LocalDate,
    val expenseMinor: Long,
    val incomeMinor: Long,
)

data class TagSpending(
    val tagId: String,
    val tagName: String,
    val expenseMinor: Long,
)

data class UpcomingPayment(
    val id: String,
    val title: String,
    val dueDate: LocalDate,
    val amountMinor: Long,
    val currencyCode: String,
    val isOverdue: Boolean = false,
)

data class DashboardTransaction(
    val id: String,
    val occurredOn: LocalDate,
    val merchantName: String?,
    val folderName: String,
    val tagName: String?,
    val kind: TransactionKind,
    val amountMinor: Long,
    val currencyCode: String,
    val note: String? = null,
    val hasReceipt: Boolean = false,
)
