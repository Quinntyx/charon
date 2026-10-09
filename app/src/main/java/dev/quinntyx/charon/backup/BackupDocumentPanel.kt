package dev.quinntyx.charon.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Minimal hostable UI for explicit backup and restore document selection. */
@Composable
fun BackupDocumentPanel(
    snapshotProvider: suspend () -> BackupSnapshot,
    restoreTarget: TransactionalRestoreTarget,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val service = remember(context) { BackupDocumentService(context.contentResolver) }
    val scope = rememberCoroutineScope()
    var duplicatePolicy by remember { mutableStateOf(DuplicatePolicy.KEEP_EXISTING) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("No backup operation in progress") }

    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CharonBackupArchive.MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = "Exporting backup…"
                try {
                    service.export(uri, snapshotProvider())
                    status = "Backup exported"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    status = "Export failed: ${error.message ?: "unknown error"}"
                } finally {
                    busy = false
                }
            }
        }
    }
    val restoreDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = "Validating backup…"
                try {
                    val result = service.restore(uri, restoreTarget, duplicatePolicy)
                    status = "Restored ${result.recordCount} records and ${result.receiptCount} receipts"
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    status = "Restore failed; existing data kept: ${error.message ?: "unknown error"}"
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Local backup")
        Text("Choose how records with matching stable IDs are handled during restore.")
        DuplicatePolicyRow(
            label = "Keep existing",
            selected = duplicatePolicy == DuplicatePolicy.KEEP_EXISTING,
            enabled = !busy,
            onSelect = { duplicatePolicy = DuplicatePolicy.KEEP_EXISTING },
        )
        DuplicatePolicyRow(
            label = "Replace existing",
            selected = duplicatePolicy == DuplicatePolicy.REPLACE_EXISTING,
            enabled = !busy,
            onSelect = { duplicatePolicy = DuplicatePolicy.REPLACE_EXISTING },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                enabled = !busy,
                onClick = { exportDocument.launch(defaultFileName()) },
            ) {
                Text("Export backup")
            }
            Button(
                enabled = !busy,
                onClick = { restoreDocument.launch(arrayOf(CharonBackupArchive.MIME_TYPE)) },
            ) {
                Text("Restore backup")
            }
        }
        Text(status)
    }
}

@Composable
private fun DuplicatePolicyRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Text(label)
    }
}

private fun defaultFileName(): String = "charon-${System.currentTimeMillis()}.${CharonBackupArchive.FILE_EXTENSION}"
