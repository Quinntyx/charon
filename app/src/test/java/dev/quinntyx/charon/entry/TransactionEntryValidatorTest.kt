package dev.quinntyx.charon.entry

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionEntryValidatorTest {
    private val wallet = EntryFolder(id = "wallet", name = "Wallet", currencyCode = "USD")
    private val checking = EntryFolder(id = "checking", name = "Checking", currencyCode = "USD")
    private val yenWallet = EntryFolder(id = "yen", name = "Yen wallet", currencyCode = "JPY")
    private val groceries = EntryTag(id = "groceries", name = "Groceries")
    private val work = EntryTag(id = "work", name = "Work")

    @Test
    fun amountParserUsesCurrencyMinorUnitsWithoutFloatingPoint() {
        assertEquals(AmountParseResult.Valid(1_234), MinorUnitAmountParser.parse("12.34", "USD"))
        assertEquals(AmountParseResult.Valid(1_234), MinorUnitAmountParser.parse("1,234", "KWD"))
        assertEquals(AmountParseResult.Valid(1_200), MinorUnitAmountParser.parse("12.000", "USD"))
        assertTrue(MinorUnitAmountParser.parse("100.5", "JPY") is AmountParseResult.Invalid)
        assertTrue(MinorUnitAmountParser.parse("1,000.00", "USD") is AmountParseResult.Invalid)
        assertTrue(MinorUnitAmountParser.parse("0", "USD") is AmountParseResult.Invalid)
    }

    @Test
    fun expenseValidationNormalizesInputAndKeepsMultipleTagsAndReceipt() {
        val receipt = ReceiptAttachment("content://dev.quinntyx.charon.receipts/42", "receipt.jpg")
        val draft = validDraft().copy(
            amount = "12,34",
            currencyCode = " usd ",
            merchant = " Corner Shop ",
            selectedTagIds = setOf(groceries.id, work.id),
            receipt = receipt,
        )

        val result = validate(draft, wallet)

        assertTrue(result is EntryValidationResult.Valid)
        val entry = (result as EntryValidationResult.Valid).entry
        assertEquals(1_234L, entry.amountMinor)
        assertEquals(LocalDate.of(2025, 6, 15), entry.date)
        assertEquals("Corner Shop", entry.merchant)
        assertEquals("USD", entry.currencyCode)
        assertEquals("wallet", entry.folderId)
        assertEquals(setOf("groceries", "work"), entry.tagIds)
        assertEquals(receipt, entry.receipt)
        assertNull(entry.transferDestinationFolderId)
    }

    @Test
    fun incomeUsesTappedFolderAndDoesNotCreateTransferDestination() {
        val result = validate(validDraft().copy(kind = TransactionKind.INCOME), checking)

        val entry = (result as EntryValidationResult.Valid).entry
        assertEquals(TransactionKind.INCOME, entry.kind)
        assertEquals("checking", entry.folderId)
        assertNull(entry.transferDestinationFolderId)
    }

    @Test
    fun transferHasExplicitSourceAndDestinationAndPreservesEditId() {
        val result = validate(
            validDraft().copy(
                transactionId = "existing-transaction",
                kind = TransactionKind.TRANSFER,
                transferDestinationFolderId = checking.id,
            ),
            wallet,
        )

        val entry = (result as EntryValidationResult.Valid).entry
        assertEquals("existing-transaction", entry.transactionId)
        assertEquals("wallet", entry.folderId)
        assertEquals("checking", entry.transferDestinationFolderId)
        assertEquals(TransactionKind.TRANSFER, entry.kind)
    }

    @Test
    fun transferRejectsSameFolderAndCrossCurrencyDestination() {
        val sameFolder = validate(
            validDraft().copy(
                kind = TransactionKind.TRANSFER,
                transferDestinationFolderId = wallet.id,
            ),
            wallet,
        ) as EntryValidationResult.Invalid
        assertTrue(EntryField.TRANSFER_DESTINATION in sameFolder.errors)

        val crossCurrency = validate(
            validDraft().copy(
                kind = TransactionKind.TRANSFER,
                transferDestinationFolderId = yenWallet.id,
            ),
            wallet,
        ) as EntryValidationResult.Invalid
        assertTrue(EntryField.TRANSFER_DESTINATION in crossCurrency.errors)
    }

    @Test
    fun rejectsFolderCurrencyMismatchUnknownTagsAndRemoteReceipt() {
        val result = validate(
            validDraft().copy(
                selectedTagIds = setOf("deleted-tag"),
                receipt = ReceiptAttachment("https://example.invalid/receipt.jpg"),
            ),
            yenWallet,
        ) as EntryValidationResult.Invalid

        assertTrue(EntryField.SAVE_FOLDER in result.errors)
        assertTrue(EntryField.TAGS in result.errors)
        assertTrue(EntryField.RECEIPT in result.errors)
    }

    @Test
    fun requiredAndMalformedEditableFieldsReportTheirOwnErrors() {
        val result = validate(
            validDraft().copy(
                amount = "-1.00",
                date = "2025-02-29",
                merchant = " ",
                currencyCode = "US dollars",
            ),
            wallet,
        ) as EntryValidationResult.Invalid

        assertTrue(EntryField.AMOUNT in result.errors)
        assertTrue(EntryField.DATE in result.errors)
        assertTrue(EntryField.MERCHANT in result.errors)
        assertTrue(EntryField.CURRENCY in result.errors)
        assertFalse(result.errors.isEmpty())
    }

    @Test
    fun ocrOnlyFillsBlankFieldsAndAllSuggestionsRemainEditableValues() {
        val draft = TransactionEntryDraft(
            amount = "9.99",
            merchant = "Corrected merchant",
            receipt = ReceiptAttachment("content://receipts/7"),
        )
        val suggestion = OcrEntrySuggestion(
            amount = "10.99",
            date = "2025-01-02",
            merchant = "OCR merchant",
            currencyCode = "EUR",
        )

        val merged = draft.withOcrSuggestion(suggestion)

        assertEquals("9.99", merged.amount)
        assertEquals("Corrected merchant", merged.merchant)
        assertEquals("2025-01-02", merged.date)
        assertEquals("EUR", merged.currencyCode)
        assertEquals("content://receipts/7", merged.receipt?.uri)
        assertEquals("8.50", merged.copy(amount = "8.50").amount)
    }

    @Test
    fun nonTransferIgnoresStaleTransferDestination() {
        val result = validate(
            validDraft().copy(
                kind = TransactionKind.EXPENSE,
                transferDestinationFolderId = checking.id,
            ),
            wallet,
        )

        val entry = (result as EntryValidationResult.Valid).entry
        assertNull(entry.transferDestinationFolderId)
    }

    private fun validDraft() = TransactionEntryDraft(
        kind = TransactionKind.EXPENSE,
        amount = "12.34",
        date = "2025-06-15",
        merchant = "Corner Shop",
        currencyCode = "USD",
    )

    private fun validate(
        draft: TransactionEntryDraft,
        tappedFolder: EntryFolder,
    ): EntryValidationResult = TransactionEntryValidator.validate(
        draft = draft,
        tappedFolder = tappedFolder,
        availableFolders = listOf(wallet, checking, yenWallet),
        availableTags = listOf(groceries, work),
    )
}
