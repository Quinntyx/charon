package dev.quinntyx.charon.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned, self-validating ZIP format used only after an explicit user export/import action. */
class CharonBackupArchive(
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxEntries: Int = 10_002,
        val maxEntryBytes: Long = 64L * 1024L * 1024L,
        val maxTotalBytes: Long = 512L * 1024L * 1024L,
        val maxRecords: Int = 250_000,
        val maxReceipts: Int = 10_000,
    )

    fun write(snapshot: BackupSnapshot, output: OutputStream) {
        validateSnapshot(snapshot)

        val sortedRecords = snapshot.records.sortedWith(compareBy(BackupRecord::collection, BackupRecord::stableId))
        val sortedReceipts = snapshot.receipts.sortedBy(BackupReceipt::stableId)
        val dataBytes = encodeRecords(sortedRecords)
        enforceEntrySize(DATA_PATH, dataBytes.size.toLong())

        val receiptDescriptors = sortedReceipts.mapIndexed { index, receipt ->
            enforceEntrySize("receipt ${receipt.stableId}", receipt.bytes.size.toLong())
            ReceiptDescriptor(
                stableId = receipt.stableId,
                mimeType = receipt.mimeType,
                path = "receipts/${index.toString().padStart(5, '0')}.bin",
                size = receipt.bytes.size.toLong(),
                sha256 = sha256(receipt.bytes),
            )
        }
        val manifestBytes = encodeManifest(snapshot, dataBytes, receiptDescriptors)
        enforceEntrySize(MANIFEST_PATH, manifestBytes.size.toLong())

        val totalBytes = manifestBytes.size.toLong() + dataBytes.size +
            sortedReceipts.sumOf { it.bytes.size.toLong() }
        if (totalBytes > limits.maxTotalBytes) {
            throw InvalidBackupException("Backup exceeds the uncompressed size limit")
        }

        ZipOutputStream(output).use { zip ->
            putEntry(zip, MANIFEST_PATH, manifestBytes)
            putEntry(zip, DATA_PATH, dataBytes)
            receiptDescriptors.zip(sortedReceipts).forEach { (descriptor, receipt) ->
                putEntry(zip, descriptor.path, receipt.bytes)
            }
        }
    }

    fun read(input: InputStream): BackupSnapshot {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    validateEntryPath(name)
                    if (entry.isDirectory) {
                        throw InvalidBackupException("Directory entries are not allowed: $name")
                    }
                    if (entries.containsKey(name)) {
                        throw InvalidBackupException("Duplicate ZIP entry: $name")
                    }
                    if (entries.size >= limits.maxEntries) {
                        throw InvalidBackupException("Backup contains too many entries")
                    }
                    val bytes = readEntry(zip, name, totalBytes)
                    totalBytes += bytes.size
                    entries[name] = bytes
                    zip.closeEntry()
                }
            }
        } catch (error: InvalidBackupException) {
            throw error
        } catch (error: IOException) {
            throw InvalidBackupException("Backup is not a readable ZIP archive", error)
        }

        return decodeAndValidate(entries)
    }

    private fun decodeAndValidate(entries: Map<String, ByteArray>): BackupSnapshot {
        val manifestBytes = entries[MANIFEST_PATH]
            ?: throw InvalidBackupException("Backup manifest is missing")
        val manifest = parseObject(manifestBytes, "manifest")
        if (manifest.optString("format") != FORMAT_NAME) {
            throw InvalidBackupException("Not a Charon backup")
        }
        val version = requiredInt(manifest, "version")
        if (version != CURRENT_VERSION) throw UnsupportedBackupVersionException(version)

        val createdAt = requiredLong(manifest, "createdAtEpochMillis")
        if (createdAt < 0) throw InvalidBackupException("Invalid backup creation time")

        val dataObject = requiredObject(manifest, "data")
        val dataPath = requiredString(dataObject, "path")
        if (dataPath != DATA_PATH) throw InvalidBackupException("Unexpected data entry path")
        val dataBytes = entries[dataPath] ?: throw InvalidBackupException("Backup data is missing")
        validateDescriptor(dataObject, dataPath, dataBytes)

        val receiptArray = requiredArray(manifest, "receipts")
        if (receiptArray.length() > limits.maxReceipts) {
            throw InvalidBackupException("Backup contains too many receipts")
        }
        val receiptIds = HashSet<String>()
        val receiptPaths = HashSet<String>()
        val receipts = ArrayList<BackupReceipt>(receiptArray.length())
        val expectedPaths = linkedSetOf(MANIFEST_PATH, DATA_PATH)
        for (index in 0 until receiptArray.length()) {
            val descriptor = receiptArray.optJSONObject(index)
                ?: throw InvalidBackupException("Invalid receipt descriptor at index $index")
            val stableId = requiredString(descriptor, "stableId")
            validateStableId(stableId, "receipt stable ID")
            if (!receiptIds.add(stableId)) {
                throw InvalidBackupException("Duplicate receipt stable ID: $stableId")
            }
            val mimeType = requiredString(descriptor, "mimeType")
            validateMimeType(mimeType)
            val path = requiredString(descriptor, "path")
            validateEntryPath(path)
            if (!path.startsWith("receipts/") || !receiptPaths.add(path)) {
                throw InvalidBackupException("Invalid or duplicate receipt path: $path")
            }
            val bytes = entries[path] ?: throw InvalidBackupException("Receipt entry is missing: $path")
            validateDescriptor(descriptor, path, bytes)
            expectedPaths += path
            receipts += BackupReceipt(stableId, mimeType, bytes)
        }

        val unexpectedPaths = entries.keys - expectedPaths
        if (unexpectedPaths.isNotEmpty()) {
            throw InvalidBackupException("Unexpected ZIP entry: ${unexpectedPaths.first()}")
        }

        val records = decodeRecords(dataBytes)
        return BackupSnapshot(createdAt, records, receipts)
    }

    private fun validateSnapshot(snapshot: BackupSnapshot) {
        if (snapshot.createdAtEpochMillis < 0) {
            throw InvalidBackupException("Invalid backup creation time")
        }
        if (snapshot.records.size > limits.maxRecords) {
            throw InvalidBackupException("Backup contains too many records")
        }
        if (snapshot.receipts.size > limits.maxReceipts) {
            throw InvalidBackupException("Backup contains too many receipts")
        }
        if (snapshot.receipts.size + 2 > limits.maxEntries) {
            throw InvalidBackupException("Backup contains too many entries")
        }

        val recordKeys = HashSet<String>()
        snapshot.records.forEach { record ->
            validateCollection(record.collection)
            validateStableId(record.stableId, "record stable ID")
            if (!recordKeys.add("${record.collection}\u0000${record.stableId}")) {
                throw InvalidBackupException("Duplicate record: ${record.collection}/${record.stableId}")
            }
            parsePayload(record.payloadJson)
        }

        val receiptIds = HashSet<String>()
        snapshot.receipts.forEach { receipt ->
            validateStableId(receipt.stableId, "receipt stable ID")
            validateMimeType(receipt.mimeType)
            if (!receiptIds.add(receipt.stableId)) {
                throw InvalidBackupException("Duplicate receipt stable ID: ${receipt.stableId}")
            }
        }
    }

    private fun encodeRecords(records: List<BackupRecord>): ByteArray {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("collection", record.collection)
                    .put("stableId", record.stableId)
                    .put("payload", parsePayload(record.payloadJson)),
            )
        }
        return JSONObject().put("records", array).toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeRecords(bytes: ByteArray): List<BackupRecord> {
        val root = parseObject(bytes, "data")
        val array = requiredArray(root, "records")
        if (array.length() > limits.maxRecords) {
            throw InvalidBackupException("Backup contains too many records")
        }
        val keys = HashSet<String>()
        val records = ArrayList<BackupRecord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw InvalidBackupException("Invalid record at index $index")
            val collection = requiredString(item, "collection")
            val stableId = requiredString(item, "stableId")
            validateCollection(collection)
            validateStableId(stableId, "record stable ID")
            val key = "$collection\u0000$stableId"
            if (!keys.add(key)) throw InvalidBackupException("Duplicate record: $collection/$stableId")
            val payload = item.optJSONObject("payload")
                ?: throw InvalidBackupException("Record payload must be a JSON object")
            records += BackupRecord(collection, stableId, payload.toString())
        }
        return records
    }

    private fun encodeManifest(
        snapshot: BackupSnapshot,
        dataBytes: ByteArray,
        receipts: List<ReceiptDescriptor>,
    ): ByteArray {
        val receiptArray = JSONArray()
        receipts.forEach { receipt ->
            receiptArray.put(
                JSONObject()
                    .put("stableId", receipt.stableId)
                    .put("mimeType", receipt.mimeType)
                    .put("path", receipt.path)
                    .put("size", receipt.size)
                    .put("sha256", receipt.sha256),
            )
        }
        return JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", CURRENT_VERSION)
            .put("createdAtEpochMillis", snapshot.createdAtEpochMillis)
            .put(
                "data",
                JSONObject()
                    .put("path", DATA_PATH)
                    .put("size", dataBytes.size.toLong())
                    .put("sha256", sha256(dataBytes)),
            )
            .put("receipts", receiptArray)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun validateDescriptor(descriptor: JSONObject, path: String, bytes: ByteArray) {
        val declaredSize = requiredLong(descriptor, "size")
        if (declaredSize != bytes.size.toLong()) {
            throw InvalidBackupException("Size mismatch for $path")
        }
        val declaredHash = requiredString(descriptor, "sha256")
        if (!HASH_PATTERN.matches(declaredHash) || declaredHash != sha256(bytes)) {
            throw InvalidBackupException("Checksum mismatch for $path")
        }
    }

    private fun parseObject(bytes: ByteArray, label: String): JSONObject = try {
        JSONObject(bytes.toString(Charsets.UTF_8))
    } catch (error: JSONException) {
        throw InvalidBackupException("Invalid $label JSON", error)
    }

    private fun parsePayload(payloadJson: String): JSONObject = try {
        JSONObject(payloadJson)
    } catch (error: JSONException) {
        throw InvalidBackupException("Record payload must be a JSON object", error)
    }

    private fun requiredObject(parent: JSONObject, key: String): JSONObject =
        parent.optJSONObject(key) ?: throw InvalidBackupException("Missing or invalid '$key'")

    private fun requiredArray(parent: JSONObject, key: String): JSONArray =
        parent.optJSONArray(key) ?: throw InvalidBackupException("Missing or invalid '$key'")

    private fun requiredString(parent: JSONObject, key: String): String = try {
        parent.getString(key)
    } catch (error: JSONException) {
        throw InvalidBackupException("Missing or invalid '$key'", error)
    }

    private fun requiredInt(parent: JSONObject, key: String): Int = try {
        parent.getInt(key)
    } catch (error: JSONException) {
        throw InvalidBackupException("Missing or invalid '$key'", error)
    }

    private fun requiredLong(parent: JSONObject, key: String): Long = try {
        parent.getLong(key)
    } catch (error: JSONException) {
        throw InvalidBackupException("Missing or invalid '$key'", error)
    }

    private fun validateCollection(collection: String) {
        if (!COLLECTION_PATTERN.matches(collection)) {
            throw InvalidBackupException("Invalid record collection: $collection")
        }
    }

    private fun validateStableId(stableId: String, label: String) {
        if (stableId.isBlank() || stableId.length > 256 || stableId.any { it.isISOControl() }) {
            throw InvalidBackupException("Invalid $label")
        }
    }

    private fun validateMimeType(mimeType: String) {
        if (mimeType.length !in 3..127 || '/' !in mimeType || mimeType.any { it.isISOControl() }) {
            throw InvalidBackupException("Invalid receipt MIME type")
        }
    }

    private fun validateEntryPath(path: String) {
        val segments = path.split('/')
        if (
            path.isBlank() || path.startsWith('/') || '\\' in path ||
            segments.any { it.isEmpty() || it == "." || it == ".." } ||
            path.any { it.isISOControl() }
        ) {
            throw InvalidBackupException("Unsafe ZIP entry path: $path")
        }
    }

    private fun readEntry(zip: ZipInputStream, name: String, bytesBeforeEntry: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            entryBytes += count
            if (entryBytes > limits.maxEntryBytes) {
                throw InvalidBackupException("ZIP entry exceeds size limit: $name")
            }
            if (bytesBeforeEntry + entryBytes > limits.maxTotalBytes) {
                throw InvalidBackupException("Backup exceeds the uncompressed size limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun enforceEntrySize(label: String, size: Long) {
        if (size > limits.maxEntryBytes) {
            throw InvalidBackupException("Entry exceeds size limit: $label")
        }
    }

    private fun putEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX_CHARS[unsigned ushr 4])
                append(HEX_CHARS[unsigned and 0x0f])
            }
        }
    }

    private data class ReceiptDescriptor(
        val stableId: String,
        val mimeType: String,
        val path: String,
        val size: Long,
        val sha256: String,
    )

    companion object {
        const val MIME_TYPE = "application/zip"
        const val FILE_EXTENSION = "charon.zip"
        const val CURRENT_VERSION = 1
        private const val FORMAT_NAME = "charon-backup"
        private const val MANIFEST_PATH = "manifest.json"
        private const val DATA_PATH = "data.json"
        private const val HEX_CHARS = "0123456789abcdef"
        private val COLLECTION_PATTERN = Regex("[a-z][a-z0-9_-]{0,63}")
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
