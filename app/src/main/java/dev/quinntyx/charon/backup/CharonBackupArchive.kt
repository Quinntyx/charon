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
        val maxMetadataEntryBytes: Long = 8L * 1024L * 1024L,
        val maxRecords: Int = 250_000,
        val maxReceipts: Int = 10_000,
    )

    /** Writes receipt sources directly into the ZIP without collecting their bytes in memory. */
    fun write(snapshot: BackupSnapshot, output: OutputStream) {
        validateSnapshot(snapshot)
        val sortedRecords = snapshot.records.sortedWith(compareBy(BackupRecord::collection, BackupRecord::stableId))
        val sortedReceipts = snapshot.receipts.sortedBy(BackupReceipt::stableId)
        var totalBytes = 0L

        ZipOutputStream(output).use { zip ->
            val dataFingerprint = writeEntry(zip, DATA_PATH, totalBytes) { entry ->
                writeRecords(sortedRecords, entry)
            }
            totalBytes = checkedTotal(totalBytes, dataFingerprint.size)

            val receiptDescriptors = sortedReceipts.mapIndexed { index, receipt ->
                val path = "receipts/${index.toString().padStart(5, '0')}.bin"
                val fingerprint = writeEntry(zip, path, totalBytes, receipt.size) { entry ->
                    val source = try {
                        receipt.openStream()
                    } catch (error: Exception) {
                        throw InvalidBackupException("Cannot open receipt content: ${receipt.stableId}", error)
                    }
                    source.use { input -> copy(input, entry) }
                }
                totalBytes = checkedTotal(totalBytes, fingerprint.size)
                ReceiptDescriptor(
                    metadata = BackupReceiptMetadata(receipt.stableId, receipt.mimeType, fingerprint.size),
                    path = path,
                    sha256 = fingerprint.sha256,
                )
            }

            val manifestBytes = encodeManifest(snapshot.createdAtEpochMillis, dataFingerprint, receiptDescriptors)
            if (manifestBytes.size.toLong() > limits.maxMetadataEntryBytes) {
                throw InvalidBackupException("Backup manifest exceeds the metadata size limit")
            }
            val manifestFingerprint = writeEntry(zip, MANIFEST_PATH, totalBytes) { entry ->
                entry.write(manifestBytes)
            }
            checkedTotal(totalBytes, manifestFingerprint.size)
        }
    }

    /**
     * Fully validates one pass without retaining receipt bodies. The returned plan is safe to use
     * only with [streamReceipts], which verifies a freshly opened second pass while streaming.
     */
    internal fun validate(input: InputStream): ValidatedBackup {
        val scan = scan(input, captureMetadata = true)
        val manifestBytes = scan.captured[MANIFEST_PATH]
            ?: throw InvalidBackupException("Backup manifest is missing")
        val dataBytes = scan.captured[DATA_PATH]
            ?: throw InvalidBackupException("Backup data is missing")
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
        validateDescriptor(
            descriptor = dataObject,
            path = dataPath,
            fingerprint = scan.entries[dataPath] ?: throw InvalidBackupException("Backup data is missing"),
        )

        val receiptArray = requiredArray(manifest, "receipts")
        if (receiptArray.length() > limits.maxReceipts) {
            throw InvalidBackupException("Backup contains too many receipts")
        }
        val receiptIds = HashSet<String>()
        val receiptPaths = HashSet<String>()
        val receipts = ArrayList<ReceiptDescriptor>(receiptArray.length())
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
            val fingerprint = scan.entries[path]
                ?: throw InvalidBackupException("Receipt entry is missing: $path")
            validateDescriptor(descriptor, path, fingerprint)
            expectedPaths += path
            receipts += ReceiptDescriptor(
                metadata = BackupReceiptMetadata(stableId, mimeType, fingerprint.size),
                path = path,
                sha256 = fingerprint.sha256,
            )
        }

        val unexpectedPaths = scan.entries.keys - expectedPaths
        if (unexpectedPaths.isNotEmpty()) {
            throw InvalidBackupException("Unexpected ZIP entry: ${unexpectedPaths.first()}")
        }

        return ValidatedBackup(
            data = BackupData(createdAt, decodeRecords(dataBytes)),
            receipts = receipts,
            entries = scan.entries,
        )
    }

    /**
     * Streams each receipt from a newly opened archive and verifies that the complete second pass
     * is byte-for-byte equivalent to the validated pass. Receipt streams are never materialized.
     */
    internal suspend fun streamReceipts(
        input: InputStream,
        validated: ValidatedBackup,
        consume: suspend (BackupReceiptMetadata, InputStream) -> Unit,
    ) {
        val receiptByPath = validated.receipts.associateBy(ReceiptDescriptor::path)
        val seen = LinkedHashSet<String>()
        var totalBytes = 0L
        val zip = ZipInputStream(input)
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validateZipEntry(name, entry.isDirectory, seen)
                seen += name
                val stream = ScanningEntryInputStream(zip, name, totalBytes)
                val receipt = receiptByPath[name]
                if (receipt == null) {
                    drain(stream)
                } else {
                    consume(receipt.metadata, stream)
                    drain(stream)
                }
                val fingerprint = stream.fingerprint()
                totalBytes = checkedTotal(totalBytes, fingerprint.size)
                val expected = validated.entries[name]
                    ?: throw InvalidBackupException("Unexpected ZIP entry: $name")
                if (fingerprint != expected) {
                    throw InvalidBackupException("Backup changed while it was being restored: $name")
                }
                zip.closeEntry()
            }
        } catch (error: InvalidBackupException) {
            throw error
        } catch (error: IOException) {
            throw InvalidBackupException("Backup is not a readable ZIP archive", error)
        } finally {
            zip.close()
        }
        if (seen != validated.entries.keys) {
            throw InvalidBackupException("Backup changed while it was being restored")
        }
    }

    private fun scan(input: InputStream, captureMetadata: Boolean): ArchiveScan {
        val entries = LinkedHashMap<String, Fingerprint>()
        val captured = HashMap<String, ByteArray>()
        var totalBytes = 0L
        val zip = ZipInputStream(input)
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validateZipEntry(name, entry.isDirectory, entries.keys)
                val capture = captureMetadata && (name == MANIFEST_PATH || name == DATA_PATH)
                val output = if (capture) ByteArrayOutputStream() else null
                val stream = ScanningEntryInputStream(zip, name, totalBytes)
                copy(stream, output)
                val fingerprint = stream.fingerprint()
                totalBytes = checkedTotal(totalBytes, fingerprint.size)
                entries[name] = fingerprint
                if (output != null) captured[name] = output.toByteArray()
                zip.closeEntry()
            }
        } catch (error: InvalidBackupException) {
            throw error
        } catch (error: IOException) {
            throw InvalidBackupException("Backup is not a readable ZIP archive", error)
        } finally {
            zip.close()
        }
        return ArchiveScan(entries, captured)
    }

    private fun validateZipEntry(name: String, isDirectory: Boolean, seen: Collection<String>) {
        validateEntryPath(name)
        if (isDirectory) throw InvalidBackupException("Directory entries are not allowed: $name")
        if (name in seen) throw InvalidBackupException("Duplicate ZIP entry: $name")
        if (seen.size >= limits.maxEntries) throw InvalidBackupException("Backup contains too many entries")
    }

    private fun validateSnapshot(snapshot: BackupSnapshot) {
        if (snapshot.createdAtEpochMillis < 0) throw InvalidBackupException("Invalid backup creation time")
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
            if (receipt.size < 0 || receipt.size > limits.maxEntryBytes) {
                throw InvalidBackupException("Invalid receipt size: ${receipt.stableId}")
            }
            if (!receiptIds.add(receipt.stableId)) {
                throw InvalidBackupException("Duplicate receipt stable ID: ${receipt.stableId}")
            }
        }
    }

    private fun writeRecords(records: List<BackupRecord>, output: OutputStream) {
        output.write("{\"records\":[".toByteArray(Charsets.UTF_8))
        records.forEachIndexed { index, record ->
            if (index > 0) output.write(','.code)
            val encoded = buildString {
                append("{\"collection\":")
                append(JSONObject.quote(record.collection))
                append(",\"stableId\":")
                append(JSONObject.quote(record.stableId))
                append(",\"payload\":")
                append(parsePayload(record.payloadJson).toString())
                append('}')
            }
            output.write(encoded.toByteArray(Charsets.UTF_8))
        }
        output.write("]}".toByteArray(Charsets.UTF_8))
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
        createdAtEpochMillis: Long,
        data: Fingerprint,
        receipts: List<ReceiptDescriptor>,
    ): ByteArray {
        val receiptArray = JSONArray()
        receipts.forEach { receipt ->
            receiptArray.put(
                JSONObject()
                    .put("stableId", receipt.metadata.stableId)
                    .put("mimeType", receipt.metadata.mimeType)
                    .put("path", receipt.path)
                    .put("size", receipt.metadata.size)
                    .put("sha256", receipt.sha256),
            )
        }
        return JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", CURRENT_VERSION)
            .put("createdAtEpochMillis", createdAtEpochMillis)
            .put(
                "data",
                JSONObject()
                    .put("path", DATA_PATH)
                    .put("size", data.size)
                    .put("sha256", data.sha256),
            )
            .put("receipts", receiptArray)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun validateDescriptor(descriptor: JSONObject, path: String, fingerprint: Fingerprint) {
        val declaredSize = requiredLong(descriptor, "size")
        if (declaredSize != fingerprint.size) throw InvalidBackupException("Size mismatch for $path")
        val declaredHash = requiredString(descriptor, "sha256")
        if (!HASH_PATTERN.matches(declaredHash) || declaredHash != fingerprint.sha256) {
            throw InvalidBackupException("Checksum mismatch for $path")
        }
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        path: String,
        bytesBeforeEntry: Long,
        expectedSize: Long? = null,
        write: (OutputStream) -> Unit,
    ): Fingerprint {
        zip.putNextEntry(ZipEntry(path).apply { time = 0L })
        val measured = MeasuringOutputStream(zip, path, bytesBeforeEntry, expectedSize)
        try {
            write(measured)
            val fingerprint = measured.fingerprint()
            if (expectedSize != null && fingerprint.size != expectedSize) {
                throw InvalidBackupException("Size mismatch while reading receipt source: $path")
            }
            return fingerprint
        } finally {
            zip.closeEntry()
        }
    }

    private inner class MeasuringOutputStream(
        private val output: OutputStream,
        private val name: String,
        private val bytesBeforeEntry: Long,
        private val expectedSize: Long?,
    ) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L

        override fun write(value: Int) {
            val byte = byteArrayOf(value.toByte())
            write(byte, 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val nextSize = size + length
            enforceReadSize(name, nextSize, bytesBeforeEntry)
            if (expectedSize != null && nextSize > expectedSize) {
                throw InvalidBackupException("Receipt source exceeds its declared size: $name")
            }
            output.write(bytes, offset, length)
            digest.update(bytes, offset, length)
            size = nextSize
        }

        fun fingerprint(): Fingerprint = Fingerprint(size, digest.digest().toHex())
    }

    private inner class ScanningEntryInputStream(
        private val input: InputStream,
        private val name: String,
        private val bytesBeforeEntry: Long,
    ) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (finished) return -1
            val count = input.read(bytes, offset, length)
            if (count < 0) {
                finished = true
                return -1
            }
            val nextSize = size + count
            enforceReadSize(name, nextSize, bytesBeforeEntry)
            digest.update(bytes, offset, count)
            size = nextSize
            return count
        }

        override fun close() {
            drain(this)
        }

        fun fingerprint(): Fingerprint {
            if (!finished) throw IllegalStateException("ZIP entry was not fully consumed")
            return Fingerprint(size, digest.digest().toHex())
        }
    }

    private fun enforceReadSize(name: String, entryBytes: Long, bytesBeforeEntry: Long) {
        if (entryBytes > limits.maxEntryBytes) {
            throw InvalidBackupException("ZIP entry exceeds size limit: $name")
        }
        if ((name == MANIFEST_PATH || name == DATA_PATH) && entryBytes > limits.maxMetadataEntryBytes) {
            throw InvalidBackupException("ZIP metadata entry exceeds size limit: $name")
        }
        if (bytesBeforeEntry > limits.maxTotalBytes - entryBytes) {
            throw InvalidBackupException("Backup exceeds the uncompressed size limit")
        }
    }

    private fun checkedTotal(current: Long, added: Long): Long {
        if (added < 0 || current > limits.maxTotalBytes - added) {
            throw InvalidBackupException("Backup exceeds the uncompressed size limit")
        }
        return current + added
    }

    private fun copy(input: InputStream, output: OutputStream?) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output?.write(buffer, 0, count)
        }
    }

    private fun drain(input: InputStream) = copy(input, null)

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

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX_CHARS[unsigned ushr 4])
            append(HEX_CHARS[unsigned and 0x0f])
        }
    }

    internal data class ValidatedBackup(
        val data: BackupData,
        val receipts: List<ReceiptDescriptor>,
        val entries: Map<String, Fingerprint>,
    )

    internal data class ReceiptDescriptor(
        val metadata: BackupReceiptMetadata,
        val path: String,
        val sha256: String,
    )

    internal data class Fingerprint(val size: Long, val sha256: String)

    private data class ArchiveScan(
        val entries: Map<String, Fingerprint>,
        val captured: Map<String, ByteArray>,
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
