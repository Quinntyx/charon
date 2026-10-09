package dev.quinntyx.charon.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Coordinates two-pass validation and transactional replacement independently of Android URIs. */
class BackupRestoreCoordinator(
    private val archive: CharonBackupArchive = CharonBackupArchive(),
) {
    /** [openInput] must return a fresh stream for each call. */
    suspend fun restore(
        openInput: () -> InputStream,
        target: TransactionalRestoreTarget,
        duplicatePolicy: DuplicatePolicy,
    ): RestoreResult {
        // No application storage mutation begins until a complete streaming pass has validated.
        val validated = openInput().use(archive::validate)
        val session = target.beginRestore()
        try {
            session.applyData(validated.data, duplicatePolicy)
            openInput().use { input ->
                archive.streamReceipts(input, validated) { receipt, content ->
                    session.applyReceipt(receipt, content)
                }
            }
            session.commit()
        } catch (failure: Throwable) {
            try {
                // Cancellation must not prevent Room or receipt-file cleanup from suspending.
                withContext(NonCancellable) {
                    session.rollback()
                }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        return RestoreResult(
            recordCount = validated.data.records.size,
            receiptCount = validated.receipts.size,
            duplicatePolicy = duplicatePolicy,
        )
    }
}
