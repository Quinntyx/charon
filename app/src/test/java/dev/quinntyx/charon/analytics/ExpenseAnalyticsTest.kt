package dev.quinntyx.charon.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseAnalyticsTest {
    private val usd = CurrencyCode.of("USD")
    private val eur = CurrencyCode.of("EUR")

    @Test
    fun timeSeriesIncludesZeroDaysAndInclusiveBoundaries() {
        val analytics = ExpenseAnalytics(
            listOf(
                transaction(1, "2024-02-28", 125, usd),
                transaction(2, "2024-02-29", 275, usd),
                transaction(3, "2024-03-01", 999, usd, type = AnalyticsTransactionType.INCOME),
                transaction(4, "2024-03-01", 999, usd, type = AnalyticsTransactionType.TRANSFER),
                transaction(5, "2024-03-02", 500, usd),
            ),
        )

        val series = analytics.expenseTimeSeries(range("2024-02-28", "2024-03-01")).single()

        assertEquals(usd, series.currency)
        assertEquals(
            listOf(
                DailyExpense(LocalDate.parse("2024-02-28"), 125),
                DailyExpense(LocalDate.parse("2024-02-29"), 275),
                DailyExpense(LocalDate.parse("2024-03-01"), 0),
            ),
            series.points,
        )
    }

    @Test
    fun rollingAveragesUseFullSevenThirtyAndNinetyDayWindows() {
        val analytics = ExpenseAnalytics(
            listOf(
                transaction(1, "2024-04-30", 700, usd),
                transaction(2, "2024-04-24", 70, usd),
                transaction(3, "2024-04-23", 300, usd),
                transaction(4, "2024-02-01", 900, usd),
                transaction(5, "2024-04-30", 5000, usd, type = AnalyticsTransactionType.TRANSFER),
            ),
        )

        val averages = analytics.rollingAverages(LocalDate.parse("2024-04-30"))
            .associateBy { it.windowDays }

        assertEquals(setOf(7, 30, 90), averages.keys)
        assertEquals(DailyAverage(totalMinor = 770, dayCount = 7), averages.getValue(7).average)
        assertEquals(DailyAverage(totalMinor = 1_070, dayCount = 30), averages.getValue(30).average)
        assertEquals(DailyAverage(totalMinor = 1_970, dayCount = 90), averages.getValue(90).average)
        assertEquals(110, averages.getValue(7).average.wholeMinorUnitsPerDay)
        assertEquals(20, averages.getValue(30).average.remainderMinorUnits)
    }

    @Test
    fun folderAndTagFiltersComposeWithoutDuplicateMatches() {
        val analytics = ExpenseAnalytics(
            listOf(
                transaction(1, "2025-01-10", 100, usd, folderId = 10, tagIds = setOf(1, 2)),
                transaction(2, "2025-01-10", 200, usd, folderId = 10, tagIds = setOf(2)),
                transaction(3, "2025-01-10", 400, usd, folderId = 20, tagIds = setOf(1, 2)),
                transaction(4, "2025-01-10", 800, usd, folderId = 10, tagIds = setOf(3)),
            ),
        )
        val dateRange = range("2025-01-10", "2025-01-10")

        val anyTag = analytics.expenseSummary(
            dateRange,
            AnalyticsFilter(folderIds = setOf(10), tagIds = setOf(1, 2)),
        )
        val allTags = analytics.expenseSummary(
            dateRange,
            AnalyticsFilter(
                folderIds = setOf(10),
                tagIds = setOf(1, 2),
                tagMatch = TagMatch.ALL,
            ),
        )

        assertEquals(300, anyTag.overall[usd])
        assertEquals(100, allTags.overall[usd])
    }

    @Test
    fun summaryDeduplicatesJoinRowsAndDoesNotBuildOverallFromTagBuckets() {
        val firstTagRow = transaction(1, "2025-02-01", 500, usd, folderId = 8, tagIds = setOf(11))
        val secondTagRow = firstTagRow.copy(tagIds = setOf(12))
        val analytics = ExpenseAnalytics(
            listOf(
                firstTagRow,
                secondTagRow,
                transaction(2, "2025-02-01", 300, usd, folderId = 8, tagIds = setOf(11)),
            ),
        )

        val summary = analytics.expenseSummary(range("2025-02-01", "2025-02-01"))

        assertEquals(800, summary.overall[usd])
        assertEquals(800, summary.byFolder.getValue(8)[usd])
        assertEquals(800, summary.byTag.getValue(11)[usd])
        assertEquals(500, summary.byTag.getValue(12)[usd])
    }

    @Test
    fun currenciesRemainSeparateAcrossEveryAggregate() {
        val analytics = ExpenseAnalytics(
            listOf(
                transaction(1, "2025-03-01", 100, usd),
                transaction(2, "2025-03-01", 250, eur),
            ),
        )

        val summary = analytics.expenseSummary(range("2025-03-01", "2025-03-01"))
        val series = analytics.expenseTimeSeries(range("2025-03-01", "2025-03-02"))

        assertEquals(mapOf(eur to 250L, usd to 100L), summary.overall.amounts)
        assertEquals(listOf(eur, usd), series.map { it.currency })
        assertEquals(listOf(250L, 0L), series.first { it.currency == eur }.points.map { it.amountMinor })
        assertEquals(listOf(100L, 0L), series.first { it.currency == usd }.points.map { it.amountMinor })
    }

    @Test
    fun periodComparisonUsesImmediateEqualPreviousPeriodAndCurrencyUnion() {
        val analytics = ExpenseAnalytics(
            listOf(
                transaction(1, "2025-03-29", 300, usd),
                transaction(2, "2025-03-30", 100, eur),
                transaction(3, "2025-03-31", 500, usd),
                transaction(4, "2025-04-01", 200, eur),
                transaction(5, "2025-04-02", 1_000, usd, type = AnalyticsTransactionType.TRANSFER),
            ),
        )

        val comparisons = analytics.compareToPreviousPeriod(range("2025-03-31", "2025-04-02"))
            .associateBy { it.currency }

        assertEquals(range("2025-03-28", "2025-03-30"), comparisons.getValue(usd).previousRange)
        assertEquals(500, comparisons.getValue(usd).current.totalMinor)
        assertEquals(300, comparisons.getValue(usd).previous.totalMinor)
        assertEquals(200, comparisons.getValue(usd).differenceMinor)
        assertEquals(200, comparisons.getValue(eur).current.totalMinor)
        assertEquals(100, comparisons.getValue(eur).previous.totalMinor)
        assertEquals(3, comparisons.getValue(eur).current.dayCount)
    }

    @Test
    fun requestedCurrencyProducesExplicitZerosForEmptyData() {
        val analytics = ExpenseAnalytics(emptyList())
        val filter = AnalyticsFilter(currencies = setOf(usd))
        val dateRange = range("2025-05-01", "2025-05-03")

        val series = analytics.expenseTimeSeries(dateRange, filter).single()
        val summary = analytics.expenseSummary(dateRange, filter)
        val averages = analytics.rollingAverages(LocalDate.parse("2025-05-03"), filter)
        val comparison = analytics.compareToPreviousPeriod(dateRange, filter).single()

        assertEquals(listOf(0L, 0L, 0L), series.points.map { it.amountMinor })
        assertEquals(0, summary.overall[usd])
        assertEquals(listOf(7, 30, 90), averages.map { it.average.dayCount })
        assertTrue(averages.all { it.average.totalMinor == 0L })
        assertEquals(0, comparison.differenceMinor)
    }

    @Test
    fun emptyUnconstrainedDataHasNoInventedCurrencySeries() {
        val analytics = ExpenseAnalytics(emptyList())
        val dateRange = range("2025-05-01", "2025-05-01")

        assertTrue(analytics.expenseTimeSeries(dateRange).isEmpty())
        assertTrue(analytics.rollingAverages(LocalDate.parse("2025-05-01")).isEmpty())
        assertTrue(analytics.expenseSummary(dateRange).overall.amounts.isEmpty())
        assertTrue(analytics.compareToPreviousPeriod(dateRange).isEmpty())
    }

    @Test
    fun invalidRangesAmountsCurrenciesAndConflictingRowsFailFast() {
        assertThrows(IllegalArgumentException::class.java) { CurrencyCode.of("US") }
        assertThrows(IllegalArgumentException::class.java) {
            DateRange(LocalDate.parse("2025-01-02"), LocalDate.parse("2025-01-01"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            transaction(1, "2025-01-01", -1, usd)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpenseAnalytics(
                listOf(
                    transaction(1, "2025-01-01", 100, usd),
                    transaction(1, "2025-01-01", 101, usd),
                ),
            )
        }
    }

    @Test
    fun exactArithmeticRejectsOverflowInsteadOfWrappingMoney() {
        val analytics = ExpenseAnalytics(
            listOf(
                transaction(1, "2025-06-01", Long.MAX_VALUE, usd),
                transaction(2, "2025-06-01", 1, usd),
            ),
        )

        assertThrows(ArithmeticException::class.java) {
            analytics.expenseSummary(range("2025-06-01", "2025-06-01"))
        }
    }

    @Test
    fun currencyCodesNormalizeWithoutLocaleDependentCaseRules() {
        assertEquals(usd, CurrencyCode.of(" usd "))
        assertEquals("USD", usd.toString())
    }

    private fun transaction(
        id: Long,
        date: String,
        amountMinor: Long,
        currency: CurrencyCode,
        type: AnalyticsTransactionType = AnalyticsTransactionType.EXPENSE,
        folderId: Long? = 1,
        tagIds: Set<Long> = emptySet(),
    ) = AnalyticsTransaction(
        transactionId = id,
        date = LocalDate.parse(date),
        amountMinor = amountMinor,
        currency = currency,
        type = type,
        folderId = folderId,
        tagIds = tagIds,
    )

    private fun range(start: String, end: String) = DateRange(
        startInclusive = LocalDate.parse(start),
        endInclusive = LocalDate.parse(end),
    )
}
