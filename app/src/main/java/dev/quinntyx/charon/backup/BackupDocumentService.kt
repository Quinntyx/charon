package dev.quinntyx.charon.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Reads and writes only URIs explicitly selected through Android's Storage Access Framework. */
class BackupDocumentService(
    private val contentResolver: ContentResolver,
    private val archive: CharonBackupArchive = CharonBackupArchive(),
    private val restoreCoordinator: BackupRestoreCoordinator = BackupRestoreCoordinator(archive),
) {
    suspend fun export(uri: Uri, snapshot: BackupSnapshot) = withContext(Dispatchers.IO) {
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: throw FileNotFoundException("The selected backup document cannot be opened")
        output.use { archive.write(snapshot, it) }
    }

    suspend fun restore(
        uri: Uri,
        target: TransactionalRestoreTarget,
        duplicatePolicy: DuplicatePolicy,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("The selected backup document cannot be opened")
        input.use { restoreCoordinator.restore(it, target, duplicatePolicy) }
    }
}
