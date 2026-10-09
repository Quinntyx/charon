package dev.quinntyx.charon.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.quinntyx.charon.domain.CurrencyTotal
import dev.quinntyx.charon.domain.NewTransaction
import dev.quinntyx.charon.domain.TransactionKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLedgerRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: RoomLedgerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "ledger-${UUID.randomUUID()}.db"
        repository = RoomLedgerRepository.open(context, databaseName)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun expenseWithMerchantReceiptAndMultipleTagsSurvivesDatabaseRestart() = runTest {
        val wallet = repository.createAccount("Everyday Wallet", "usd")
        val merchant = repository.getOrCreateMerchant("Corner Market")
        val groceries = repository.getOrCreateTag("Groceries")
        val essential = repository.getOrCreateTag("Essential")
        val receipt = repository.addReceiptReference(
            localUri = "content://dev.quinntyx.charon.receipts/receipt-1",
            displayName = "receipt.jpg",
            mimeType = "image/jpeg",
            capturedAtEpochMillis = 1_700_000_000_000,
            sha256 = "A".repeat(64),
        )

        val saved = repository.record(
            NewTransaction.Expense(
                amountMinor = 1_299,
                currencyCode = "USD",
                occurredAtEpochMillis = 1_700_000_001_000,
                sourceAccountId = wallet.id,
                merchantId = merchant.id,
                receiptId = receipt.id,
                tagIds = setOf(groceries.id, essential.id),
                note = "Weekly shop",
            ),
        )

        repository.close()
        repository = RoomLedgerRepository.open(context, databaseName)

        val restored = repository.getTransaction(saved.id)
        assertNotNull(restored)
        restored!!
        assertEquals(TransactionKind.EXPENSE, restored.kind)
        assertEquals(1_299L, restored.amountMinor)
        assertEquals("USD", restored.currencyCode)
        assertEquals(wallet.id, restored.sourceAccount?.id)
        assertNull(restored.destinationAccount)
        assertEquals(merchant.id, restored.merchant?.id)
        assertEquals(receipt.localUri, restored.receipt?.localUri)
        assertEquals("a".repeat(64), restored.receipt?.sha256)
        assertEquals(setOf("Essential", "Groceries"), restored.tags.map { it.name }.toSet())
        assertEquals("Weekly shop", restored.note)
    }

    @Test
    fun transactionShapeAndCurrencyInvariantsAreEnforced() = runTest {
        val usdSource = repository.createAccount("USD source", "USD")
        val usdDestination = repository.createAccount("USD destination", "USD")
        val eurDestination = repository.createAccount("EUR destination", "EUR")
        val archived = repository.createAccount("Old wallet", "USD")
        repository.updateAccount(archived.id, archived.name, archived = true)
        val archivedTag = repository.getOrCreateTag("Retired")
        repository.updateTag(archivedTag.id, archivedTag.name, archived = true)

        assertInvalid("positive") {
            repository.record(
                NewTransaction.Expense(0, "USD", 1, usdSource.id),
            )
        }
        assertInvalid("does not match") {
            repository.record(
                NewTransaction.Expense(100, "EUR", 1, usdSource.id),
            )
        }
        assertInvalid("must differ") {
            repository.record(
                NewTransaction.Transfer(100, "USD", 1, usdSource.id, usdSource.id),
            )
        }
        assertInvalid("currencies to match") {
            repository.record(
                NewTransaction.Transfer(100, "USD", 1, usdSource.id, eurDestination.id),
            )
        }
        assertInvalid("Archived account") {
            repository.record(
                NewTransaction.Income(100, "USD", 1, archived.id),
            )
        }
        assertInvalid("Archived tags") {
            repository.record(
                NewTransaction.Expense(100, "USD", 1, usdSource.id, tagIds = setOf(archivedTag.id)),
            )
        }

        val transfer = repository.record(
            NewTransaction.Transfer(100, "USD", 2, usdSource.id, usdDestination.id),
        )
        assertEquals(TransactionKind.TRANSFER, transfer.kind)
        assertEquals(usdSource.id, transfer.sourceAccount?.id)
        assertEquals(usdDestination.id, transfer.destinationAccount?.id)
        assertNull(transfer.merchant)
    }

    @Test
    fun expenseTotalsExcludeIncomeAndTransfersAndNeverMixCurrencies() = runTest {
        val usdChecking = repository.createAccount("Checking", "USD")
        val usdSavings = repository.createAccount("Savings", "USD")
        val eurCash = repository.createAccount("Cash", "EUR")

        repository.record(NewTransaction.Expense(1_000, "USD", 10, usdChecking.id))
        repository.record(NewTransaction.Income(9_000, "USD", 11, usdChecking.id))
        repository.record(NewTransaction.Transfer(5_000, "USD", 12, usdChecking.id, usdSavings.id))
        repository.record(NewTransaction.Expense(700, "EUR", 13, eurCash.id))
        repository.record(NewTransaction.Expense(300, "USD", 100, usdChecking.id))

        assertEquals(
            listOf(CurrencyTotal("EUR", 700), CurrencyTotal("USD", 1_000)),
            repository.getExpenseTotals(10, 100),
        )
    }

    @Test
    fun accountTagAndMerchantIdentitiesAreIndependentAndNamesAreNormalized() = runTest {
        val account = repository.createAccount("  Corner   Market ", "USD")
        val merchant = repository.getOrCreateMerchant("Corner Market")
        val sameMerchant = repository.getOrCreateMerchant(" corner market ")
        val tag = repository.getOrCreateTag("Corner Market")

        assertEquals("Corner Market", account.name)
        assertEquals(merchant.id, sameMerchant.id)
        assertTrue(account.id > 0)
        assertTrue(merchant.id > 0)
        assertTrue(tag.id > 0)

        repository.updateAccount(account.id, "Daily wallet", archived = true)
        repository.updateTag(tag.id, "Shops", archived = true)
        assertTrue(repository.getAccounts().isEmpty())
        assertTrue(repository.getTags().isEmpty())
        assertEquals(1, repository.getAccounts(includeArchived = true).size)
        assertEquals(1, repository.getTags(includeArchived = true).size)
        assertFalse(repository.getMerchants().isEmpty())
    }

    @Test
    fun receiptReferencesMustBeLocalAndCanOnlyBeAttachedOnce() = runTest {
        val account = repository.createAccount("Wallet", "USD")
        assertInvalid("on-device") {
            repository.addReceiptReference(
                localUri = "https://example.invalid/receipt.jpg",
                capturedAtEpochMillis = 1,
            )
        }
        val receipt = repository.addReceiptReference(
            localUri = "file:///data/user/0/dev.quinntyx.charon/files/receipt.jpg",
            capturedAtEpochMillis = 1,
        )
        repository.record(
            NewTransaction.Expense(100, "USD", 2, account.id, receiptId = receipt.id),
        )

        assertInvalid("already attached") {
            repository.record(
                NewTransaction.Expense(200, "USD", 3, account.id, receiptId = receipt.id),
            )
        }
        assertEquals(1, repository.getTransactions().size)
    }

    private suspend fun assertInvalid(messageFragment: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException containing '$messageFragment'")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "Expected '${error.message}' to contain '$messageFragment'",
                error.message.orEmpty().contains(messageFragment, ignoreCase = true),
            )
        }
    }
}
