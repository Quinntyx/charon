package dev.quinntyx.charon.dashboard

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

internal object MoneyFormatter {
    fun format(minorUnits: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
        val fractionDigits = currency?.defaultFractionDigits?.takeIf { it >= 0 } ?: 2
        val majorUnits = BigDecimal.valueOf(minorUnits).movePointLeft(fractionDigits)
        return if (currency != null) {
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = currency
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
            }.format(majorUnits)
        } else {
            "$currencyCode ${majorUnits.setScale(fractionDigits).toPlainString()}"
        }
    }
}
