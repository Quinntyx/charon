package dev.quinntyx.charon.dashboard

import dev.quinntyx.charon.analytics.AnalyticsFilter
import dev.quinntyx.charon.analytics.AnalyticsTransaction
import dev.quinntyx.charon.analytics.AnalyticsTransactionType
import dev.quinntyx.charon.analytics.CurrencyCode
import dev.quinntyx.charon.analytics.DateRange
import dev.quinntyx.charon.analytics.ExpenseAnalytics
import dev.quinntyx.charon.domain.LedgerRepository
import dev.quinntyx.charon.domain.LedgerTransaction
import dev.quinntyx.charon.recurrence.RecurrenceService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomDashboardRepository(
    private val ledger: LedgerRepository,
    private val recurrence: RecurrenceService,
    private val clock: Clock = Clock.systemDefaultZone(),
) : DashboardRepository {
    private val zone: ZoneId get() = clock.zone

    override fun observeDashboard(period: DashboardPeriod): Flow<DashboardSnapshot> = combine(
        ledger.observeTransactions(),
        recurrence.repository.observePayments(),
        recurrence.repository.observeLoggedOccurrences(),
    ) { transactions, payments, logs ->
        val today = LocalDate.now(clock)
        val range = DateRange(today.minusDays(period.dayCount.toLong() - 1L), today)
        val loggedKeys = logs.mapTo(mutableSetOf()) { it.occurrence.key }
        val upcoming = payments.flatMap { payment ->
            recurrence.scheduledOccurrences(
                payment,
                fromInclusive = minOf(payment.startsOn, today),
                toInclusive = today.plusDays(90),
            )
        }.filterNot { it.key in loggedKeys }
            .sortedWith(compareBy({ it.key.dueDate }, { it.title }))
            .map {
                UpcomingPayment(
                    id = "${it.key.recurringPaymentId}:${it.key.dueDate}",
                    title = it.title,
                    dueDate = it.key.dueDate,
                    amountMinor = it.amountMinorUnits,
                    currencyCode = it.currency,
                    isOverdue = it.key.dueDate < today,
                )
            }

        val analyticsTransactions = transactions.map { it.toAnalytics() }
        val analytics = ExpenseAnalytics(analyticsTransactions)
        val inRange = transactions.filter { it.occurredDate() in range }
        val currencies = (inRange.map { it.currencyCode } + upcoming.map { it.currencyCode })
            .distinct().sorted()
        val tagNames = transactions.flatMap { it.tags }.associate { it.id to it.name }
        val folderNames = transactions.flatMap { listOfNotNull(it.sourceAccount, it.destinationAccount) }
            .associate { it.id to it.name }

        val overviews = currencies.map { currency ->
            val currencyCode = CurrencyCode.of(currency)
            val filter = AnalyticsFilter(currencies = setOf(currencyCode))
            val series = analytics.expenseTimeSeries(range, filter).single()
            val summary = analytics.expenseSummary(range, filter)
            val rolling = analytics.rollingAverages(today, filter, setOf(period.dayCount)).single()
            val currencyTransactions = inRange.filter { it.currencyCode == currency }
            val incomeByDate = currencyTransactions.asSequence()
                .filter { it.kind == dev.quinntyx.charon.domain.TransactionKind.INCOME }
                .groupingBy { it.occurredDate() }
                .fold(0L) { total, transaction -> Math.addExact(total, transaction.amountMinor) }
            CurrencyOverview(
                currencyCode = currency,
                rangeStart = range.startInclusive,
                rangeEnd = range.endInclusive,
                dailyActivity = series.points.map { point ->
                    DailyActivity(point.date, point.amountMinor, incomeByDate[point.date] ?: 0L)
                },
                expenseTotalMinor = summary.overall[currencyCode],
                incomeTotalMinor = currencyTransactions.asSequence()
                    .filter { it.kind == dev.quinntyx.charon.domain.TransactionKind.INCOME }
                    .fold(0L) { total, transaction -> Math.addExact(total, transaction.amountMinor) },
                rollingExpenseAverageMinor = rolling.average.wholeMinorUnitsPerDay,
                rollingAverageWindowDays = period.dayCount,
                tagSpending = summary.byTag.mapNotNull { (id, totals) ->
                    val amount = totals[currencyCode]
                    tagNames[id]?.let { TagSpending(id.toString(), it, amount) }
                },
                folderSpending = summary.byFolder.mapNotNull { (id, totals) ->
                    val amount = totals[currencyCode]
                    folderNames[id]?.let { FolderSpending(id.toString(), it, amount) }
                },
            )
        }

        DashboardSnapshot(
            period = period,
            currencies = overviews,
            upcomingPayments = upcoming,
            recentTransactions = transactions.take(100).map { it.toDashboard() },
        )
    }

    private fun LedgerTransaction.toAnalytics() = AnalyticsTransaction(
        transactionId = id,
        date = occurredDate(),
        amountMinor = amountMinor,
        currency = CurrencyCode.of(currencyCode),
        type = when (kind) {
            dev.quinntyx.charon.domain.TransactionKind.EXPENSE -> AnalyticsTransactionType.EXPENSE
            dev.quinntyx.charon.domain.TransactionKind.INCOME -> AnalyticsTransactionType.INCOME
            dev.quinntyx.charon.domain.TransactionKind.TRANSFER -> AnalyticsTransactionType.TRANSFER
        },
        folderId = sourceAccount?.id ?: destinationAccount?.id,
        tagIds = tags.mapTo(mutableSetOf()) { it.id },
    )

    private fun LedgerTransaction.toDashboard() = DashboardTransaction(
        id = id.toString(),
        occurredOn = occurredDate(),
        merchantName = merchant?.name,
        folderName = when (kind) {
            dev.quinntyx.charon.domain.TransactionKind.EXPENSE -> sourceAccount?.name.orEmpty()
            dev.quinntyx.charon.domain.TransactionKind.INCOME -> destinationAccount?.name.orEmpty()
            dev.quinntyx.charon.domain.TransactionKind.TRANSFER ->
                "${sourceAccount?.name.orEmpty()} → ${destinationAccount?.name.orEmpty()}"
        },
        tagName = tags.joinToString { it.name }.ifBlank { null },
        kind = when (kind) {
            dev.quinntyx.charon.domain.TransactionKind.EXPENSE -> TransactionKind.EXPENSE
            dev.quinntyx.charon.domain.TransactionKind.INCOME -> TransactionKind.INCOME
            dev.quinntyx.charon.domain.TransactionKind.TRANSFER -> TransactionKind.TRANSFER
        },
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        note = note,
        hasReceipt = receipt != null,
    )

    private fun LedgerTransaction.occurredDate(): LocalDate =
        Instant.ofEpochMilli(occurredAtEpochMillis).atZone(zone).toLocalDate()
}
