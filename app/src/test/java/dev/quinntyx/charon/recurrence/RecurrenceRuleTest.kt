package dev.quinntyx.charon.recurrence

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceRuleTest {
    @Test
    fun monthlyMonthEndKeepsOriginalAnchor() {
        val dates = occurrenceDatesBetween(
            rule = RecurrenceRule.Monthly(),
            startsOn = LocalDate.parse("2023-01-31"),
            fromInclusive = LocalDate.parse("2023-01-01"),
            toInclusive = LocalDate.parse("2023-04-30"),
        )

        assertEquals(
            listOf("2023-01-31", "2023-02-28", "2023-03-31", "2023-04-30").map(LocalDate::parse),
            dates,
        )
    }

    @Test
    fun monthlyMonthEndUsesLeapDayWhenAvailable() {
        val dates = occurrenceDatesBetween(
            RecurrenceRule.Monthly(),
            LocalDate.parse("2024-01-31"),
            LocalDate.parse("2024-01-01"),
            LocalDate.parse("2024-03-31"),
        )

        assertEquals(
            listOf("2024-01-31", "2024-02-29", "2024-03-31").map(LocalDate::parse),
            dates,
        )
    }

    @Test
    fun yearlyLeapDayReturnsToLeapDayInsteadOfPermanentlyClamping() {
        val dates = occurrenceDatesBetween(
            RecurrenceRule.Yearly(),
            LocalDate.parse("2020-02-29"),
            LocalDate.parse("2020-01-01"),
            LocalDate.parse("2024-12-31"),
        )

        assertEquals(
            listOf("2020-02-29", "2021-02-28", "2022-02-28", "2023-02-28", "2024-02-29")
                .map(LocalDate::parse),
            dates,
        )
    }

    @Test
    fun weeklyAndCustomIntervalsAreCalendarBased() {
        assertEquals(
            listOf("2025-01-06", "2025-01-20", "2025-02-03").map(LocalDate::parse),
            occurrenceDatesBetween(
                RecurrenceRule.Weekly(everyWeeks = 2),
                LocalDate.parse("2025-01-06"),
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-02-03"),
            ),
        )
        assertEquals(
            listOf("2025-01-01", "2025-01-11", "2025-01-21", "2025-01-31").map(LocalDate::parse),
            occurrenceDatesBetween(
                RecurrenceRule.CustomDays(everyDays = 10),
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-01-31"),
            ),
        )
    }

    @Test
    fun emptyAndPreStartRangesAreEmpty() {
        assertTrue(
            occurrenceDatesBetween(
                RecurrenceRule.Weekly(),
                LocalDate.parse("2025-01-10"),
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-01-09"),
            ).isEmpty(),
        )
    }
}
