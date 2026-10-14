package dev.quinntyx.charon.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MoneyFormatterTest {
    @Test
    fun formatsIntegerMinorUnitsWithoutFloatingPoint() {
        assertEquals("$1,234.56", MoneyFormatter.format(123_456L, "USD", Locale.US))
        assertEquals("-$0.05", MoneyFormatter.format(-5L, "USD", Locale.US))
    }

    @Test
    fun respectsCurrenciesWithNoFractionalMinorUnits() {
        assertEquals("¥1,234", MoneyFormatter.format(1_234L, "JPY", Locale.US))
    }

    @Test
    fun unknownCurrencyUsesExplicitCodeAndTwoDigitFallback() {
        assertEquals("ZZZ 12.34", MoneyFormatter.format(1_234L, "ZZZ", Locale.US))
    }
}
