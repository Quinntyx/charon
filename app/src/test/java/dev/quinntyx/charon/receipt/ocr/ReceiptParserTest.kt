package dev.quinntyx.charon.receipt.ocr

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {
    private val parser = ReceiptParser()

    @Test
    fun `selects total instead of subtotal tender and change`() {
        val parsed = parser.parse(
            """
            CORNER MARKET
            10 Main Street
            Date: 2025-06-14
            Subtotal       $18.50
            Tax             $1.50
            TOTAL          $20.00
            Cash Tendered  $50.00
            Change         $30.00
            """.trimIndent(),
        )

        assertEquals("CORNER MARKET", parsed.merchant?.value)
        assertEquals(2_000L, parsed.total?.value?.minorUnits)
        assertEquals(LocalDate.of(2025, 6, 14), parsed.date?.value)
        assertEquals("USD", parsed.currency?.value?.value)
        assertFalse(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_TOTAL))
        assertTrue(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_CURRENCY))
    }

    @Test
    fun `parses European grouping and decimal separators into minor units`() {
        val parsed = parser.parse(
            """
            BAECKEREI SONNE
            Datum 14/06/2025
            Zwischensumme 1.100,00 €
            GRAND TOTAL 1.234,56 €
            """.trimIndent(),
        )

        assertEquals(123_456L, parsed.total?.value?.minorUnits)
        assertEquals("EUR", parsed.currency?.value?.value)
        assertFalse(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_CURRENCY))
    }

    @Test
    fun `prefixed dollar symbol resolves currency without treating tender as total`() {
        val parsed = parser.parse(
            """
            MAPLE GROCER
            Transaction date 2025-01-31
            TOTAL CA$42.19
            VISA CA$100.00
            """.trimIndent(),
        )

        assertEquals(4_219L, parsed.total?.value?.minorUnits)
        assertEquals("CAD", parsed.currency?.value?.value)
        assertFalse(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_CURRENCY))
    }

    @Test
    fun `zero-decimal currency does not multiply whole amount`() {
        val parsed = parser.parse(
            """
            TOKYO SHOP
            JPY
            Date 2025-02-20
            Total ¥1,234
            """.trimIndent(),
        )

        assertEquals(1_234L, parsed.total?.value?.minorUnits)
        assertEquals("JPY", parsed.currency?.value?.value)
        assertFalse(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_CURRENCY))
    }

    @Test
    fun `three-decimal currency is converted to its minor units`() {
        val parsed = parser.parse(
            """
            KUWAIT SHOP
            KWD
            Date 2025-02-20
            Total 1.234
            """.trimIndent(),
        )

        assertEquals(1_234L, parsed.total?.value?.minorUnits)
        assertEquals("KWD", parsed.currency?.value?.value)
    }

    @Test
    fun `conflicting equally plausible totals are exposed as ambiguous`() {
        val parsed = parser.parse(
            """
            OAK CAFE
            USD
            Date 2025-05-01
            TOTAL 12.00
            TOTAL 13.00
            """.trimIndent(),
        )

        assertEquals(1_200L, parsed.total?.value?.minorUnits)
        assertEquals(listOf(ParsedMoney(1_300L)), parsed.total?.alternatives)
        assertTrue(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_TOTAL))
    }

    @Test
    fun `ambiguous numeric date keeps both interpretations for review`() {
        val parsed = parser.parse(
            """
            NORTH BOOKS
            USD
            Date 03/04/2025
            Total 8.99
            """.trimIndent(),
        )

        assertEquals(LocalDate.of(2025, 3, 4), parsed.date?.value)
        assertEquals(listOf(LocalDate.of(2025, 4, 3)), parsed.date?.alternatives)
        assertTrue(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_DATE))
    }

    @Test
    fun `day above twelve makes numeric date unambiguous`() {
        val parsed = parser.parse(
            """
            NORTH BOOKS
            EUR
            Date 23/04/2025
            Total 8,99
            """.trimIndent(),
        )

        assertEquals(LocalDate.of(2025, 4, 23), parsed.date?.value)
        assertFalse(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_DATE))
    }

    @Test
    fun `two equally plausible headings expose merchant ambiguity`() {
        val parsed = parser.parse(
            """
            Alpha Market
            BETA CAFE
            USD
            Date 2025-05-01
            Total 9.00
            """.trimIndent(),
        )

        assertEquals("Alpha Market", parsed.merchant?.value)
        assertEquals(listOf("BETA CAFE"), parsed.merchant?.alternatives)
        assertTrue(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_MERCHANT))
    }

    @Test
    fun `currency-marked item prices are not promoted when total is missing`() {
        val parsed = parser.parse(
            """
            RIVER MARKET
            EUR
            Date 2025-06-14
            Bread €2.49
            Milk €1.20
            """.trimIndent(),
        )

        assertNull(parsed.total)
        assertEquals(setOf(ReceiptParseIssue.MISSING_TOTAL), parsed.issues)
        assertTrue(parsed.requiresReview)
    }

    @Test
    fun `invalid named calendar dates are rejected instead of adjusted`() {
        listOf("Date February 30, 2025", "Date 31 Apr 2025").forEach { invalidDate ->
            val parsed = parser.parse(
                """
                CITY STORE
                USD
                $invalidDate
                Total 12.00
                """.trimIndent(),
            )

            assertNull(parsed.date)
            assertTrue(parsed.issues.contains(ReceiptParseIssue.MISSING_DATE))
        }
    }

    @Test
    fun `missing fields are explicit and subtotal alone is not promoted`() {
        val parsed = parser.parse(
            """
            Receipt
            Subtotal 9.00
            Tax 1.00
            Thank you
            """.trimIndent(),
        )

        assertNull(parsed.merchant)
        assertNull(parsed.total)
        assertNull(parsed.date)
        assertNull(parsed.currency)
        assertEquals(
            setOf(
                ReceiptParseIssue.MISSING_MERCHANT,
                ReceiptParseIssue.MISSING_TOTAL,
                ReceiptParseIssue.MISSING_DATE,
                ReceiptParseIssue.MISSING_CURRENCY,
            ),
            parsed.issues,
        )
        assertTrue(parsed.requiresReview)
    }

    @Test
    fun `explicit ISO currency wins over ambiguous dollar symbol`() {
        val parsed = parser.parse(
            """
            CITY STORE
            USD
            Purchased June 7, 2025
            Amount due $17.45
            """.trimIndent(),
        )

        assertEquals("USD", parsed.currency?.value?.value)
        assertEquals(LocalDate.of(2025, 6, 7), parsed.date?.value)
        assertFalse(parsed.issues.contains(ReceiptParseIssue.AMBIGUOUS_CURRENCY))
    }
}
