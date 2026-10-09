package dev.quinntyx.charon.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Coordinates validation and transactional replacement independently of Android URI handling. */
class BackupRestoreCoordinator(
    private val archive: CharonBackupArchive = CharonBackupArchive(),
) {
    suspend fun restore(
        input: InputStream,
        target: TransactionalRestoreTarget,
        duplicatePolicy: DuplicatePolicy,
    ): RestoreResult {
        // No storage mutation begins until the complete archive has passed validation.
        val snapshot = archive.read(input)
        val session = target.beginRestore()
        try {
            session.apply(snapshot, duplicatePolicy)
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
        return RestoreResult(snapshot.records.size, snapshot.receipts.size, duplicatePolicy)
    }
}
