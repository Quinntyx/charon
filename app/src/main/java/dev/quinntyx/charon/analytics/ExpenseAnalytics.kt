package dev.quinntyx.charon.analytics

import java.time.LocalDate

/** Pure, deterministic expense analytics over a persistence projection. */
class ExpenseAnalytics(transactions: Iterable<AnalyticsTransaction>) {
    private val transactions: List<AnalyticsTransaction> = canonicalTransactions(transactions)

    /**
     * Returns daily expense points for every day in [range], including zero-spend days.
     * Income and transfers never contribute to the series.
     */
    fun expenseTimeSeries(
        range: DateRange,
        filter: AnalyticsFilter = AnalyticsFilter(),
    ): List<CurrencyExpenseSeries> {
        val expenses = matchingExpenses(range, filter)
        val currencies = currencyUniverse(filter, expenses)
        val totalsByCurrencyAndDate = expenses
            .groupBy { it.currency }
            .mapValues { (_, currencyExpenses) ->
                currencyExpenses.groupingBy { it.date }.fold(0L) { total, transaction ->
                    Math.addExact(total, transaction.amountMinor)
                }
            }

        return currencies.map { currency ->
            val totalsByDate = totalsByCurrencyAndDate[currency].orEmpty()
            CurrencyExpenseSeries(
                currency = currency,
                range = range,
                points = List(range.dayCount) { dayIndex ->
                    val date = range.startInclusive.plusDays(dayIndex.toLong())
                    DailyExpense(date, totalsByDate[date] ?: 0L)
                },
            )
        }
    }

    /**
     * Computes exact 7/30/90-day averages by default. The denominator is always the full calendar
     * window, not the number of days that contain transactions.
     */
    fun rollingAverages(
        asOfInclusive: LocalDate,
        filter: AnalyticsFilter = AnalyticsFilter(),
        windows: Set<Int> = DEFAULT_ROLLING_WINDOWS,
    ): List<RollingAverage> {
        require(windows.isNotEmpty()) { "At least one rolling window is required" }
        require(windows.all { it > 0 }) { "Rolling windows must be positive" }

        val sortedWindows = windows.sorted()
        val ranges = sortedWindows.associateWith { windowDays ->
            DateRange(
                startInclusive = asOfInclusive.minusDays(windowDays.toLong() - 1L),
                endInclusive = asOfInclusive,
            )
        }
        val longestRange = ranges.getValue(sortedWindows.last())
        val currencies = currencyUniverse(filter, matchingExpenses(longestRange, filter))

        return currencies.flatMap { currency ->
            sortedWindows.map { windowDays ->
                val range = ranges.getValue(windowDays)
                val total = matchingExpenses(range, filter)
                    .asSequence()
                    .filter { it.currency == currency }
                    .fold(0L) { sum, transaction -> Math.addExact(sum, transaction.amountMinor) }
                RollingAverage(
                    currency = currency,
                    windowDays = windowDays,
                    range = range,
                    average = DailyAverage(totalMinor = total, dayCount = windowDays),
                )
            }
        }
    }

    /**
     * Produces overall, folder, and tag totals. Transactions duplicated by persistence joins are
     * canonicalized by ID before aggregation, and multi-tag rows never inflate [ExpenseSummary.overall].
     */
    fun expenseSummary(
        range: DateRange,
        filter: AnalyticsFilter = AnalyticsFilter(),
    ): ExpenseSummary {
        val expenses = matchingExpenses(range, filter)
        val currencies = currencyUniverse(filter, expenses)
        val zeroTotals = currencies.associateWith { 0L }

        val overall = expenses.fold(zeroTotals) { totals, transaction ->
            totals.add(transaction.currency, transaction.amountMinor)
        }

        val byFolder = expenses
            .mapNotNull { transaction -> transaction.folderId?.let { it to transaction } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .mapValues { (_, folderExpenses) -> totals(folderExpenses, currencies) }

        val byTag = expenses
            .flatMap { transaction -> transaction.tagIds.map { tagId -> tagId to transaction } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .mapValues { (_, tagExpenses) -> totals(tagExpenses, currencies) }

        return ExpenseSummary(
            range = range,
            overall = CurrencyTotals(overall),
            byFolder = byFolder,
            byTag = byTag,
        )
    }

    /** Compares arbitrary inclusive periods while keeping each currency in a separate result. */
    fun compareExpensePeriods(
        currentRange: DateRange,
        previousRange: DateRange,
        filter: AnalyticsFilter = AnalyticsFilter(),
    ): List<ExpensePeriodComparison> {
        val currentExpenses = matchingExpenses(currentRange, filter)
        val previousExpenses = matchingExpenses(previousRange, filter)
        val currencies = currencyUniverse(filter, currentExpenses + previousExpenses)

        return currencies.map { currency ->
            val currentTotal = totalForCurrency(currentExpenses, currency)
            val previousTotal = totalForCurrency(previousExpenses, currency)
            ExpensePeriodComparison(
                currency = currency,
                currentRange = currentRange,
                previousRange = previousRange,
                current = DailyAverage(currentTotal, currentRange.dayCount),
                previous = DailyAverage(previousTotal, previousRange.dayCount),
                differenceMinor = Math.subtractExact(currentTotal, previousTotal),
            )
        }
    }

    /** Compares [currentRange] with the immediately preceding period of equal length. */
    fun compareToPreviousPeriod(
        currentRange: DateRange,
        filter: AnalyticsFilter = AnalyticsFilter(),
    ): List<ExpensePeriodComparison> = compareExpensePeriods(
        currentRange = currentRange,
        previousRange = currentRange.previousEqualLength(),
        filter = filter,
    )

    private fun matchingExpenses(
        range: DateRange,
        filter: AnalyticsFilter,
    ): List<AnalyticsTransaction> = transactions.filter { transaction ->
        transaction.type == AnalyticsTransactionType.EXPENSE &&
            transaction.date in range &&
            (filter.folderIds.isEmpty() || transaction.folderId in filter.folderIds) &&
            matchesTags(transaction.tagIds, filter) &&
            (filter.currencies.isEmpty() || transaction.currency in filter.currencies)
    }

    private fun matchesTags(transactionTags: Set<Long>, filter: AnalyticsFilter): Boolean {
        if (filter.tagIds.isEmpty()) return true
        return when (filter.tagMatch) {
            TagMatch.ANY -> transactionTags.any { it in filter.tagIds }
            TagMatch.ALL -> transactionTags.containsAll(filter.tagIds)
        }
    }

    private fun currencyUniverse(
        filter: AnalyticsFilter,
        expenses: Collection<AnalyticsTransaction>,
    ): List<CurrencyCode> = (filter.currencies.ifEmpty { expenses.mapTo(mutableSetOf()) { it.currency } })
        .sorted()

    private fun totals(
        expenses: List<AnalyticsTransaction>,
        currencies: List<CurrencyCode>,
    ): CurrencyTotals = CurrencyTotals(
        expenses.fold(currencies.associateWith { 0L }) { totals, transaction ->
            totals.add(transaction.currency, transaction.amountMinor)
        },
    )

    private fun totalForCurrency(
        expenses: List<AnalyticsTransaction>,
        currency: CurrencyCode,
    ): Long = expenses.asSequence()
        .filter { it.currency == currency }
        .fold(0L) { sum, transaction -> Math.addExact(sum, transaction.amountMinor) }

    private fun Map<CurrencyCode, Long>.add(
        currency: CurrencyCode,
        amountMinor: Long,
    ): Map<CurrencyCode, Long> = toMutableMap().apply {
        this[currency] = Math.addExact(get(currency) ?: 0L, amountMinor)
    }

    private fun canonicalTransactions(
        source: Iterable<AnalyticsTransaction>,
    ): List<AnalyticsTransaction> {
        val byId = LinkedHashMap<Long, AnalyticsTransaction>()
        source.forEach { transaction ->
            val immutableTransaction = transaction.copy(tagIds = transaction.tagIds.toSet())
            val existing = byId[transaction.transactionId]
            if (existing == null) {
                byId[transaction.transactionId] = immutableTransaction
            } else {
                require(existing.copy(tagIds = emptySet()) == immutableTransaction.copy(tagIds = emptySet())) {
                    "Conflicting analytics rows share transaction ID ${transaction.transactionId}"
                }
                byId[transaction.transactionId] = existing.copy(
                    tagIds = existing.tagIds + immutableTransaction.tagIds,
                )
            }
        }
        return byId.values.toList()
    }

    companion object {
        val DEFAULT_ROLLING_WINDOWS: Set<Int> = linkedSetOf(7, 30, 90)
    }
}
