package dev.quinntyx.charon.data.local

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import dev.quinntyx.charon.backup.BackupData
import dev.quinntyx.charon.backup.BackupReceipt
import dev.quinntyx.charon.backup.BackupReceiptMetadata
import dev.quinntyx.charon.backup.BackupRecord
import dev.quinntyx.charon.backup.BackupSnapshot
import dev.quinntyx.charon.backup.DuplicatePolicy
import dev.quinntyx.charon.backup.InvalidBackupException
import dev.quinntyx.charon.backup.RestoreSession
import dev.quinntyx.charon.backup.TransactionalRestoreTarget
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

class RoomBackupAdapter internal constructor(
    private val database: CharonDatabase,
    private val contentResolver: ContentResolver,
    private val filesDir: File,
    private val cacheDir: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : TransactionalRestoreTarget {
    private val dao = database.charonDao()

    suspend fun snapshot(): BackupSnapshot = database.withTransaction {
        val receipts = dao.getReceipts()
        BackupSnapshot(
            createdAtEpochMillis = nowEpochMillis(),
            records = buildList {
                dao.getAccounts(true).forEach { add(it.toBackup()) }
                dao.getMerchants().forEach { add(it.toBackup()) }
                dao.getTags(true).forEach { add(it.toBackup()) }
                receipts.forEach { add(it.toBackup()) }
                dao.getTransactions().forEach { add(it.toBackup()) }
                dao.getAllTransactionTags().forEach { add(it.toBackup()) }
                dao.getRecurringPayments().forEach { add(it.toBackup()) }
                dao.getAllRecurringPaymentTags().forEach { add(it.toBackup()) }
                dao.getLoggedOccurrences().forEach { add(it.toBackup()) }
            },
            receipts = receipts.map { receipt ->
                val uri = Uri.parse(receipt.localUri)
                val size = contentLength(uri)
                BackupReceipt(
                    stableId = receipt.id.toString(),
                    mimeType = receipt.mimeType ?: "image/jpeg",
                    size = size,
                    openContent = {
                        contentResolver.openInputStream(uri)
                            ?: throw InvalidBackupException("Receipt ${receipt.id} cannot be opened")
                    },
                )
            },
        )
    }

    override suspend fun beginRestore(): RestoreSession = Session(
        stagingDirectory = File(cacheDir, "restore-${UUID.randomUUID()}").also {
            if (!it.mkdirs()) throw InvalidBackupException("Could not create restore staging storage")
        },
    )

    private fun contentLength(uri: Uri): Long {
        if (uri.scheme == "file") {
            val file = requireNotNull(uri.path) { "Receipt file URI has no path" }.let(::File)
            if (!file.isFile) throw InvalidBackupException("Receipt file is missing: ${file.name}")
            return file.length()
        }
        val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (length < 0) throw InvalidBackupException("Receipt size is unavailable for $uri")
        return length
    }

    private inner class Session(private val stagingDirectory: File) : RestoreSession {
        private var data: BackupData? = null
        private var duplicatePolicy: DuplicatePolicy = DuplicatePolicy.KEEP_EXISTING
        private val stagedReceipts = linkedMapOf<String, Pair<BackupReceiptMetadata, File>>()
        private val movedFiles = mutableListOf<File>()
        private var finished = false

        override suspend fun applyData(data: BackupData, duplicatePolicy: DuplicatePolicy) {
            check(!finished) { "Restore session is already finished" }
            check(this.data == null) { "Backup data was already applied" }
            this.data = data
            this.duplicatePolicy = duplicatePolicy
        }

        override suspend fun applyReceipt(receipt: BackupReceiptMetadata, content: InputStream) {
            check(!finished) { "Restore session is already finished" }
            if (receipt.stableId in stagedReceipts) throw InvalidBackupException("Duplicate restored receipt ${receipt.stableId}")
            val file = File(stagingDirectory, "${safeId(receipt.stableId)}.bin")
            FileOutputStream(file).use { output ->
                var remaining = receipt.size
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val read = content.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw InvalidBackupException("Receipt ${receipt.stableId} ended early")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.fd.sync()
            }
            stagedReceipts[receipt.stableId] = receipt to file
        }

        override suspend fun commit() {
            check(!finished) { "Restore session is already finished" }
            val backupData = requireNotNull(data) { "Backup data was not applied" }
            val records = backupData.records.groupBy(BackupRecord::collection)
            val restoredUris = mutableMapOf<Long, String>()
            val receiptRecords = records[COLLECTION_RECEIPTS].orEmpty().associateBy { it.stableId }
            if (receiptRecords.keys != stagedReceipts.keys) {
                throw InvalidBackupException("Receipt records and receipt content do not match")
            }
            try {
                for ((stableId, pair) in stagedReceipts) {
                    val id = stableId.toLongOrNull() ?: throw InvalidBackupException("Invalid receipt id $stableId")
                    if (duplicatePolicy == DuplicatePolicy.KEEP_EXISTING && dao.getReceipt(id) != null) continue
                    val metadata = pair.first
                    val destinationDirectory = File(filesDir, "receipt-images").also {
                        if (!it.exists() && !it.mkdirs()) throw InvalidBackupException("Could not create receipt storage")
                    }
                    val extension = extensionForMime(metadata.mimeType)
                    val destination = File(destinationDirectory, "restored-$id-${UUID.randomUUID()}.$extension")
                    Files.move(pair.second.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    movedFiles += destination
                    restoredUris[id] = Uri.fromFile(destination).toString()
                }

                database.withTransaction {
                    restoreAccounts(records[COLLECTION_ACCOUNTS].orEmpty())
                    restoreMerchants(records[COLLECTION_MERCHANTS].orEmpty())
                    restoreTags(records[COLLECTION_TAGS].orEmpty())
                    restoreReceipts(records[COLLECTION_RECEIPTS].orEmpty(), restoredUris)
                    restoreTransactions(records[COLLECTION_TRANSACTIONS].orEmpty())
                    restoreTransactionTags(records[COLLECTION_TRANSACTION_TAGS].orEmpty())
                    restoreRecurringPayments(records[COLLECTION_RECURRING].orEmpty())
                    restoreRecurringTags(records[COLLECTION_RECURRING_TAGS].orEmpty())
                    restoreLogged(records[COLLECTION_LOGGED].orEmpty())
                }
                finished = true
                stagingDirectory.deleteRecursively()
            } catch (error: Throwable) {
                movedFiles.forEach(File::delete)
                throw error
            }
        }

        override suspend fun rollback() {
            if (!finished) {
                movedFiles.forEach(File::delete)
                stagingDirectory.deleteRecursively()
                finished = true
            }
        }

        private suspend fun restoreAccounts(records: List<BackupRecord>) = records.forEach { record ->
            val value = record.payload()
            val entity = AccountEntity(
                id = value.getLong("id"), name = value.getString("name"),
                normalizedName = value.getString("normalizedName"), currencyCode = value.getString("currencyCode"),
                archived = value.getBoolean("archived"),
            )
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertAccount(entity) else dao.insertAccount(entity)
        }

        private suspend fun restoreMerchants(records: List<BackupRecord>) = records.forEach { record ->
            val value = record.payload()
            val entity = MerchantEntity(value.getLong("id"), value.getString("name"), value.getString("normalizedName"))
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertMerchant(entity) else dao.insertMerchant(entity)
        }

        private suspend fun restoreTags(records: List<BackupRecord>) = records.forEach { record ->
            val value = record.payload()
            val entity = TagEntity(value.getLong("id"), value.getString("name"), value.getString("normalizedName"), value.getBoolean("archived"))
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertTag(entity) else dao.insertTag(entity)
        }

        private suspend fun restoreReceipts(records: List<BackupRecord>, restoredUris: Map<Long, String>) = records.forEach { record ->
            val value = record.payload()
            val id = value.getLong("id")
            val entity = ReceiptReferenceEntity(
                id = id,
                localUri = restoredUris[id] ?: value.getString("localUri"),
                displayName = value.nullableString("displayName"),
                mimeType = value.nullableString("mimeType"),
                capturedAtEpochMillis = value.getLong("capturedAtEpochMillis"),
                sha256 = value.nullableString("sha256"),
            )
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertReceipt(entity) else dao.insertReceiptIgnoring(entity)
        }

        private suspend fun restoreTransactions(records: List<BackupRecord>) = records.forEach { record ->
            val value = record.payload()
            val entity = LedgerTransactionEntity(
                id = value.getLong("id"), kind = value.getString("kind"), amountMinor = value.getLong("amountMinor"),
                currencyCode = value.getString("currencyCode"), occurredAtEpochMillis = value.getLong("occurredAtEpochMillis"),
                sourceAccountId = value.nullableLong("sourceAccountId"), destinationAccountId = value.nullableLong("destinationAccountId"),
                merchantId = value.nullableLong("merchantId"), receiptId = value.nullableLong("receiptId"),
                note = value.nullableString("note"), createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
            )
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertTransaction(entity) else dao.insertTransactionIgnoring(entity)
        }

        private suspend fun restoreTransactionTags(records: List<BackupRecord>) {
            val rows = records.map { record ->
                val value = record.payload()
                TransactionTagEntity(value.getLong("transactionId"), value.getLong("tagId"))
            }
            if (rows.isNotEmpty()) dao.insertTransactionTagsIgnoring(rows)
        }

        private suspend fun restoreRecurringPayments(records: List<BackupRecord>) = records.forEach { record ->
            val value = record.payload()
            val entity = RecurringPaymentEntity(
                id = value.getString("id"), title = value.getString("title"), currencyCode = value.getString("currencyCode"),
                folderAccountId = value.getLong("folderAccountId"), merchantId = value.nullableLong("merchantId"),
                startsOnEpochDay = value.getLong("startsOnEpochDay"), ruleType = value.getString("ruleType"),
                ruleInterval = value.getInt("ruleInterval"), loggingPolicy = value.getString("loggingPolicy"),
                amountRevisionsJson = value.getString("amountRevisionsJson"), skippedDatesJson = value.getString("skippedDatesJson"),
                pauseWindowsJson = value.getString("pauseWindowsJson"), pausedSinceEpochDay = value.nullableLong("pausedSinceEpochDay"),
                cancelledFromEpochDay = value.nullableLong("cancelledFromEpochDay"),
            )
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertRecurringPayment(entity)
            else dao.insertRecurringPaymentIgnoring(entity)
        }

        private suspend fun restoreRecurringTags(records: List<BackupRecord>) {
            val rows = records.map { record ->
                val value = record.payload()
                RecurringPaymentTagEntity(value.getString("recurringPaymentId"), value.getLong("tagId"))
            }
            if (rows.isNotEmpty()) dao.insertRecurringPaymentTags(rows)
        }

        private suspend fun restoreLogged(records: List<BackupRecord>) = records.forEach { record ->
            val value = record.payload()
            val entity = LoggedOccurrenceEntity(
                recurringPaymentId = value.getString("recurringPaymentId"), dueEpochDay = value.getLong("dueEpochDay"),
                title = value.getString("title"), currencyCode = value.getString("currencyCode"), amountMinor = value.getLong("amountMinor"),
                folderAccountId = value.getLong("folderAccountId"), merchantId = value.nullableLong("merchantId"),
                tagIdsJson = value.getString("tagIdsJson"), loggingPolicy = value.getString("loggingPolicy"),
                loggedAtEpochMillis = value.getLong("loggedAtEpochMillis"), origin = value.getString("origin"),
                ledgerTransactionId = value.getLong("ledgerTransactionId"),
            )
            if (duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING) dao.upsertLoggedOccurrence(entity)
            else dao.insertLoggedOccurrence(entity)
        }
    }

    private companion object {
        const val COLLECTION_ACCOUNTS = "accounts"
        const val COLLECTION_MERCHANTS = "merchants"
        const val COLLECTION_TAGS = "tags"
        const val COLLECTION_RECEIPTS = "receipts"
        const val COLLECTION_TRANSACTIONS = "transactions"
        const val COLLECTION_TRANSACTION_TAGS = "transaction_tags"
        const val COLLECTION_RECURRING = "recurring_payments"
        const val COLLECTION_RECURRING_TAGS = "recurring_payment_tags"
        const val COLLECTION_LOGGED = "logged_occurrences"

        fun safeId(value: String): String {
            if (!value.matches(Regex("[A-Za-z0-9_-]+"))) throw InvalidBackupException("Unsafe receipt id")
            return value
        }

        fun extensionForMime(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    }
}

private fun BackupRecord.payload(): JSONObject = try { JSONObject(payloadJson) }
catch (error: Exception) { throw InvalidBackupException("Invalid record payload for $collection/$stableId", error) }

private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
private fun record(collection: String, stableId: String, payload: JSONObject) = BackupRecord(collection, stableId, payload.toString())

private fun AccountEntity.toBackup() = record("accounts", id.toString(), JSONObject().put("id", id).put("name", name).put("normalizedName", normalizedName).put("currencyCode", currencyCode).put("archived", archived))
private fun MerchantEntity.toBackup() = record("merchants", id.toString(), JSONObject().put("id", id).put("name", name).put("normalizedName", normalizedName))
private fun TagEntity.toBackup() = record("tags", id.toString(), JSONObject().put("id", id).put("name", name).put("normalizedName", normalizedName).put("archived", archived))
private fun ReceiptReferenceEntity.toBackup() = record("receipts", id.toString(), JSONObject().put("id", id).put("localUri", localUri).putNullable("displayName", displayName).putNullable("mimeType", mimeType).put("capturedAtEpochMillis", capturedAtEpochMillis).putNullable("sha256", sha256))
private fun LedgerTransactionEntity.toBackup() = record("transactions", id.toString(), JSONObject().put("id", id).put("kind", kind).put("amountMinor", amountMinor).put("currencyCode", currencyCode).put("occurredAtEpochMillis", occurredAtEpochMillis).putNullable("sourceAccountId", sourceAccountId).putNullable("destinationAccountId", destinationAccountId).putNullable("merchantId", merchantId).putNullable("receiptId", receiptId).putNullable("note", note).put("createdAtEpochMillis", createdAtEpochMillis))
private fun TransactionTagEntity.toBackup() = record("transaction_tags", "$transactionId:$tagId", JSONObject().put("transactionId", transactionId).put("tagId", tagId))
private fun RecurringPaymentEntity.toBackup() = record("recurring_payments", id, JSONObject().put("id", id).put("title", title).put("currencyCode", currencyCode).put("folderAccountId", folderAccountId).putNullable("merchantId", merchantId).put("startsOnEpochDay", startsOnEpochDay).put("ruleType", ruleType).put("ruleInterval", ruleInterval).put("loggingPolicy", loggingPolicy).put("amountRevisionsJson", amountRevisionsJson).put("skippedDatesJson", skippedDatesJson).put("pauseWindowsJson", pauseWindowsJson).putNullable("pausedSinceEpochDay", pausedSinceEpochDay).putNullable("cancelledFromEpochDay", cancelledFromEpochDay))
private fun RecurringPaymentTagEntity.toBackup() = record("recurring_payment_tags", "$recurringPaymentId:$tagId", JSONObject().put("recurringPaymentId", recurringPaymentId).put("tagId", tagId))
private fun LoggedOccurrenceEntity.toBackup() = record("logged_occurrences", "$recurringPaymentId:$dueEpochDay", JSONObject().put("recurringPaymentId", recurringPaymentId).put("dueEpochDay", dueEpochDay).put("title", title).put("currencyCode", currencyCode).put("amountMinor", amountMinor).put("folderAccountId", folderAccountId).putNullable("merchantId", merchantId).put("tagIdsJson", tagIdsJson).put("loggingPolicy", loggingPolicy).put("loggedAtEpochMillis", loggedAtEpochMillis).put("origin", origin).put("ledgerTransactionId", ledgerTransactionId))
