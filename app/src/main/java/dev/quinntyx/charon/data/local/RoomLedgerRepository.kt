package dev.quinntyx.charon.data.local

import android.content.Context
import androidx.room.withTransaction
import dev.quinntyx.charon.domain.Account
import dev.quinntyx.charon.domain.CurrencyTotal
import dev.quinntyx.charon.domain.LedgerRepository
import dev.quinntyx.charon.domain.LedgerTransaction
import dev.quinntyx.charon.domain.Merchant
import dev.quinntyx.charon.domain.NewTransaction
import dev.quinntyx.charon.domain.ReceiptReference
import dev.quinntyx.charon.domain.Tag
import dev.quinntyx.charon.domain.TransactionKind
import java.io.Closeable
import java.net.URI
import java.util.Currency
import java.util.Locale

class RoomLedgerRepository private constructor(
    private val database: CharonDatabase,
    private val nowEpochMillis: () -> Long,
) : LedgerRepository, Closeable {
    private val dao = database.charonDao()

    override suspend fun createAccount(name: String, currencyCode: String): Account =
        database.withTransaction {
            val cleanName = cleanName(name)
            val normalizedName = normalizeName(cleanName)
            val currency = normalizeCurrency(currencyCode)
            dao.findAccount(normalizedName, currency)?.toDomain()
                ?: run {
                    val candidate = AccountEntity(
                        name = cleanName,
                        normalizedName = normalizedName,
                        currencyCode = currency,
                    )
                    val id = dao.insertAccount(candidate)
                    if (id == INSERT_CONFLICT) {
                        checkNotNull(dao.findAccount(normalizedName, currency)).toDomain()
                    } else {
                        candidate.copy(id = id).toDomain()
                    }
                }
        }

    override suspend fun updateAccount(id: Long, name: String, archived: Boolean): Account =
        database.withTransaction {
            val current = requireNotNull(dao.getAccount(id)) { "Unknown account id $id" }
            val cleanName = cleanName(name)
            val normalizedName = normalizeName(cleanName)
            val duplicate = dao.findAccount(normalizedName, current.currencyCode)
            require(duplicate == null || duplicate.id == id) {
                "An account named '$cleanName' already exists for ${current.currencyCode}"
            }
            current.copy(
                name = cleanName,
                normalizedName = normalizedName,
                archived = archived,
            ).also { dao.updateAccount(it) }.toDomain()
        }

    override suspend fun getAccounts(includeArchived: Boolean): List<Account> =
        dao.getAccounts(includeArchived).map(AccountEntity::toDomain)

    override suspend fun getOrCreateMerchant(name: String): Merchant =
        database.withTransaction {
            val cleanName = cleanName(name)
            val normalizedName = normalizeName(cleanName)
            dao.findMerchant(normalizedName)?.toDomain()
                ?: run {
                    val candidate = MerchantEntity(name = cleanName, normalizedName = normalizedName)
                    val id = dao.insertMerchant(candidate)
                    if (id == INSERT_CONFLICT) {
                        checkNotNull(dao.findMerchant(normalizedName)).toDomain()
                    } else {
                        candidate.copy(id = id).toDomain()
                    }
                }
        }

    override suspend fun renameMerchant(id: Long, name: String): Merchant =
        database.withTransaction {
            val current = requireNotNull(dao.getMerchant(id)) { "Unknown merchant id $id" }
            val cleanName = cleanName(name)
            val normalizedName = normalizeName(cleanName)
            val duplicate = dao.findMerchant(normalizedName)
            require(duplicate == null || duplicate.id == id) {
                "A merchant named '$cleanName' already exists"
            }
            current.copy(name = cleanName, normalizedName = normalizedName)
                .also { dao.updateMerchant(it) }
                .toDomain()
        }

    override suspend fun getMerchants(): List<Merchant> =
        dao.getMerchants().map(MerchantEntity::toDomain)

    override suspend fun getOrCreateTag(name: String): Tag =
        database.withTransaction {
            val cleanName = cleanName(name)
            val normalizedName = normalizeName(cleanName)
            dao.findTag(normalizedName)?.toDomain()
                ?: run {
                    val candidate = TagEntity(name = cleanName, normalizedName = normalizedName)
                    val id = dao.insertTag(candidate)
                    if (id == INSERT_CONFLICT) {
                        checkNotNull(dao.findTag(normalizedName)).toDomain()
                    } else {
                        candidate.copy(id = id).toDomain()
                    }
                }
        }

    override suspend fun updateTag(id: Long, name: String, archived: Boolean): Tag =
        database.withTransaction {
            val current = requireNotNull(dao.getTag(id)) { "Unknown tag id $id" }
            val cleanName = cleanName(name)
            val normalizedName = normalizeName(cleanName)
            val duplicate = dao.findTag(normalizedName)
            require(duplicate == null || duplicate.id == id) {
                "A tag named '$cleanName' already exists"
            }
            current.copy(name = cleanName, normalizedName = normalizedName, archived = archived)
                .also { dao.updateTag(it) }
                .toDomain()
        }

    override suspend fun getTags(includeArchived: Boolean): List<Tag> =
        dao.getTags(includeArchived).map(TagEntity::toDomain)

    override suspend fun addReceiptReference(
        localUri: String,
        displayName: String?,
        mimeType: String?,
        capturedAtEpochMillis: Long,
        sha256: String?,
    ): ReceiptReference {
        require(capturedAtEpochMillis >= 0) { "Receipt capture time cannot be negative" }
        val cleanUri = localUri.trim()
        val scheme = runCatching { URI(cleanUri).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        require(scheme == "content" || scheme == "file") {
            "Receipt URI must be an on-device content:// or file:// reference"
        }
        val cleanHash = sha256?.trim()?.lowercase(Locale.ROOT)
        require(cleanHash == null || SHA_256.matches(cleanHash)) {
            "Receipt SHA-256 must contain exactly 64 hexadecimal characters"
        }
        val receipt = ReceiptReferenceEntity(
            localUri = cleanUri,
            displayName = cleanOptionalText(displayName, MAX_SHORT_TEXT_LENGTH),
            mimeType = cleanOptionalText(mimeType, MAX_SHORT_TEXT_LENGTH),
            capturedAtEpochMillis = capturedAtEpochMillis,
            sha256 = cleanHash,
        )
        return receipt.copy(id = dao.insertReceipt(receipt)).toDomain()
    }

    override suspend fun record(transaction: NewTransaction): LedgerTransaction =
        database.withTransaction {
            require(transaction.amountMinor > 0) { "Transaction amount must be positive minor units" }
            require(transaction.occurredAtEpochMillis >= 0) { "Transaction time cannot be negative" }
            val currency = normalizeCurrency(transaction.currencyCode)
            val tags = if (transaction.tagIds.isEmpty()) {
                emptyList()
            } else {
                dao.getTags(transaction.tagIds).also { found ->
                    require(found.size == transaction.tagIds.size) { "One or more tag ids do not exist" }
                    require(found.none(TagEntity::archived)) { "Archived tags cannot be added to a transaction" }
                }
            }
            transaction.receiptId?.let { receiptId ->
                requireNotNull(dao.getReceipt(receiptId)) { "Unknown receipt id $receiptId" }
                require(dao.countTransactionsUsingReceipt(receiptId) == 0) {
                    "Receipt id $receiptId is already attached to a transaction"
                }
            }

            val resolved = when (transaction) {
                is NewTransaction.Expense -> {
                    val source = requireActiveAccount(transaction.sourceAccountId)
                    require(source.currencyCode == currency) {
                        "Expense currency $currency does not match account currency ${source.currencyCode}"
                    }
                    transaction.merchantId?.let { merchantId ->
                        requireNotNull(dao.getMerchant(merchantId)) { "Unknown merchant id $merchantId" }
                    }
                    ResolvedTransaction(
                        kind = TransactionKind.EXPENSE,
                        sourceAccountId = source.id,
                        merchantId = transaction.merchantId,
                    )
                }

                is NewTransaction.Income -> {
                    val destination = requireActiveAccount(transaction.destinationAccountId)
                    require(destination.currencyCode == currency) {
                        "Income currency $currency does not match account currency ${destination.currencyCode}"
                    }
                    transaction.merchantId?.let { merchantId ->
                        requireNotNull(dao.getMerchant(merchantId)) { "Unknown merchant id $merchantId" }
                    }
                    ResolvedTransaction(
                        kind = TransactionKind.INCOME,
                        destinationAccountId = destination.id,
                        merchantId = transaction.merchantId,
                    )
                }

                is NewTransaction.Transfer -> {
                    require(transaction.sourceAccountId != transaction.destinationAccountId) {
                        "Transfer source and destination accounts must differ"
                    }
                    val source = requireActiveAccount(transaction.sourceAccountId)
                    val destination = requireActiveAccount(transaction.destinationAccountId)
                    require(source.currencyCode == currency && destination.currencyCode == currency) {
                        "Transfers require source, destination, and transaction currencies to match"
                    }
                    ResolvedTransaction(
                        kind = TransactionKind.TRANSFER,
                        sourceAccountId = source.id,
                        destinationAccountId = destination.id,
                    )
                }
            }

            val entity = LedgerTransactionEntity(
                kind = resolved.kind.name,
                amountMinor = transaction.amountMinor,
                currencyCode = currency,
                occurredAtEpochMillis = transaction.occurredAtEpochMillis,
                sourceAccountId = resolved.sourceAccountId,
                destinationAccountId = resolved.destinationAccountId,
                merchantId = resolved.merchantId,
                receiptId = transaction.receiptId,
                note = cleanOptionalText(transaction.note, MAX_NOTE_LENGTH),
                createdAtEpochMillis = nowEpochMillis(),
            )
            val id = dao.insertTransaction(entity)
            if (tags.isNotEmpty()) {
                dao.insertTransactionTags(tags.map { TransactionTagEntity(id, it.id) })
            }
            mapTransaction(entity.copy(id = id), tags)
        }

    override suspend fun getTransaction(id: Long): LedgerTransaction? =
        database.withTransaction {
            dao.getTransaction(id)?.let { mapTransaction(it, dao.getTagsForTransaction(it.id)) }
        }

    override suspend fun getTransactions(): List<LedgerTransaction> =
        database.withTransaction {
            dao.getTransactions().map { mapTransaction(it, dao.getTagsForTransaction(it.id)) }
        }

    override suspend fun getExpenseTotals(
        fromEpochMillisInclusive: Long,
        toEpochMillisExclusive: Long,
    ): List<CurrencyTotal> {
        require(fromEpochMillisInclusive >= 0) { "Start time cannot be negative" }
        require(toEpochMillisExclusive > fromEpochMillisInclusive) {
            "End time must be after start time"
        }
        return dao.getExpenseTotals(fromEpochMillisInclusive, toEpochMillisExclusive)
            .map { CurrencyTotal(it.currencyCode, it.amountMinor) }
    }

    override fun close() {
        database.close()
    }

    private suspend fun requireActiveAccount(id: Long): AccountEntity =
        requireNotNull(dao.getAccount(id)) { "Unknown account id $id" }
            .also { require(!it.archived) { "Archived account id $id cannot record new transactions" } }

    private suspend fun mapTransaction(
        entity: LedgerTransactionEntity,
        tags: List<TagEntity>,
    ): LedgerTransaction = LedgerTransaction(
        id = entity.id,
        kind = TransactionKind.valueOf(entity.kind),
        amountMinor = entity.amountMinor,
        currencyCode = entity.currencyCode,
        occurredAtEpochMillis = entity.occurredAtEpochMillis,
        sourceAccount = entity.sourceAccountId?.let { checkNotNull(dao.getAccount(it)).toDomain() },
        destinationAccount = entity.destinationAccountId?.let { checkNotNull(dao.getAccount(it)).toDomain() },
        merchant = entity.merchantId?.let { dao.getMerchant(it)?.toDomain() },
        receipt = entity.receiptId?.let { checkNotNull(dao.getReceipt(it)).toDomain() },
        tags = tags.map(TagEntity::toDomain),
        note = entity.note,
        createdAtEpochMillis = entity.createdAtEpochMillis,
    )

    private data class ResolvedTransaction(
        val kind: TransactionKind,
        val sourceAccountId: Long? = null,
        val destinationAccountId: Long? = null,
        val merchantId: Long? = null,
    )

    companion object {
        const val DEFAULT_DATABASE_NAME = "charon.db"
        private const val INSERT_CONFLICT = -1L
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_SHORT_TEXT_LENGTH = 255
        private const val MAX_NOTE_LENGTH = 2_000
        private val WHITESPACE = Regex("\\s+")
        private val SHA_256 = Regex("[0-9a-f]{64}")

        fun open(
            context: Context,
            databaseName: String = DEFAULT_DATABASE_NAME,
        ): RoomLedgerRepository = RoomLedgerRepository(
            database = CharonDatabase.open(context, databaseName),
            nowEpochMillis = System::currentTimeMillis,
        )

        private fun cleanName(value: String): String {
            val clean = value.trim().replace(WHITESPACE, " ")
            require(clean.isNotEmpty()) { "Name cannot be blank" }
            require(clean.length <= MAX_NAME_LENGTH) { "Name cannot exceed $MAX_NAME_LENGTH characters" }
            return clean
        }

        private fun normalizeName(value: String): String = value.lowercase(Locale.ROOT)

        private fun normalizeCurrency(value: String): String {
            val normalized = value.trim().uppercase(Locale.ROOT)
            require(normalized.length == 3) { "Currency must be a three-letter ISO 4217 code" }
            return try {
                Currency.getInstance(normalized).currencyCode
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown ISO 4217 currency code '$normalized'")
            }
        }

        private fun cleanOptionalText(value: String?, maxLength: Int): String? {
            val clean = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            require(clean.length <= maxLength) { "Text cannot exceed $maxLength characters" }
            return clean
        }
    }
}

private fun AccountEntity.toDomain() = Account(id, name, currencyCode, archived)
private fun MerchantEntity.toDomain() = Merchant(id, name)
private fun TagEntity.toDomain() = Tag(id, name, archived)
private fun ReceiptReferenceEntity.toDomain() = ReceiptReference(
    id = id,
    localUri = localUri,
    displayName = displayName,
    mimeType = mimeType,
    capturedAtEpochMillis = capturedAtEpochMillis,
    sha256 = sha256,
)
