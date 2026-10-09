package dev.quinntyx.charon.backup

import kotlinx.coroutines.CancellationException
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
                session.rollback()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            if (failure is CancellationException) throw failure
            throw failure
        }
        return RestoreResult(snapshot.records.size, snapshot.receipts.size, duplicatePolicy)
    }
}
