package dev.quinntyx.charon.backup

import java.io.ByteArrayInputStream
import java.io.InputStream

/** A logical application record with a stable identity chosen by its owning repository. */
data class BackupRecord(
    val collection: String,
    val stableId: String,
    val payloadJson: String,
)

/**
 * Repeat-free receipt source used during export. Production callers should use the streaming
 * constructor so receipt files are not accumulated in the Android heap.
 */
class BackupReceipt(
    val stableId: String,
    val mimeType: String,
    val size: Long,
    private val openContent: () -> InputStream,
) {
    /** Convenience for small values and tests. The streaming constructor is preferred for files. */
    constructor(stableId: String, mimeType: String, bytes: ByteArray) : this(
        stableId = stableId,
        mimeType = mimeType,
        size = bytes.size.toLong(),
        openContent = { ByteArrayInputStream(bytes) },
    )

    fun openStream(): InputStream = openContent()
}

data class BackupSnapshot(
    val createdAtEpochMillis: Long,
    val records: List<BackupRecord>,
    val receipts: List<BackupReceipt>,
)

/** Validated non-binary backup content passed to a restore transaction. */
data class BackupData(
    val createdAtEpochMillis: Long,
    val records: List<BackupRecord>,
)

/** Metadata for receipt content that is streamed separately during restore. */
data class BackupReceiptMetadata(
    val stableId: String,
    val mimeType: String,
    val size: Long,
)

enum class DuplicatePolicy {
    /** Preserve records already on the device when their collection and stable ID match. */
    KEEP_EXISTING,

    /** Replace records already on the device when their collection and stable ID match. */
    REPLACE_EXISTING,
}

/**
 * The persistence owner must implement this as a real database transaction plus staged receipt
 * file operations. Receipt [content] is valid only for the duration of [applyReceipt], so it must
 * be copied to staging storage before the method returns. The coordinator always calls [rollback]
 * after a failed apply or commit.
 */
interface RestoreSession {
    suspend fun applyData(data: BackupData, duplicatePolicy: DuplicatePolicy)
    suspend fun applyReceipt(receipt: BackupReceiptMetadata, content: InputStream)
    suspend fun commit()
    suspend fun rollback()
}

fun interface TransactionalRestoreTarget {
    suspend fun beginRestore(): RestoreSession
}

data class RestoreResult(
    val recordCount: Int,
    val receiptCount: Int,
    val duplicatePolicy: DuplicatePolicy,
)

open class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InvalidBackupException(message: String, cause: Throwable? = null) :
    BackupException(message, cause)

class UnsupportedBackupVersionException(version: Int) :
    BackupException("Unsupported Charon backup version: $version")
