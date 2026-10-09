package dev.quinntyx.charon.backup

/** A logical application record with a stable identity chosen by its owning repository. */
data class BackupRecord(
    val collection: String,
    val stableId: String,
    val payloadJson: String,
)

/** Receipt bytes are kept in the archive rather than encoded into JSON. */
data class BackupReceipt(
    val stableId: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is BackupReceipt &&
            stableId == other.stableId &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * stableId.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}

data class BackupSnapshot(
    val createdAtEpochMillis: Long,
    val records: List<BackupRecord>,
    val receipts: List<BackupReceipt>,
)

enum class DuplicatePolicy {
    /** Preserve records already on the device when their collection and stable ID match. */
    KEEP_EXISTING,

    /** Replace records already on the device when their collection and stable ID match. */
    REPLACE_EXISTING,
}

/**
 * The persistence owner must implement this as a real database transaction (and staged receipt
 * file operation). The coordinator always calls [rollback] after a failed apply or commit.
 */
interface RestoreSession {
    suspend fun apply(snapshot: BackupSnapshot, duplicatePolicy: DuplicatePolicy)
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
