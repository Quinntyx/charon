package dev.quinntyx.charon.entry

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inputs supplied by folder/tag, capture, OCR, and persistence owners. */
data class TransactionEntryConfig(
    val folders: List<EntryFolder>,
    val tags: List<EntryTag>,
    val initialDraft: TransactionEntryDraft = TransactionEntryDraft(),
    val ocrSuggestion: OcrEntrySuggestion? = null,
)

/** Hoistable editable state for both create and edit flows. */
@Stable
class TransactionEntryState internal constructor(initialDraft: TransactionEntryDraft) {
    var draft by mutableStateOf(initialDraft)
        private set

    fun setKind(value: TransactionKind) {
        draft = draft.copy(
            kind = value,
            transferDestinationFolderId = if (value == TransactionKind.TRANSFER) {
                draft.transferDestinationFolderId
            } else {
                null
            },
        )
    }

    fun setAmount(value: String) {
        draft = draft.copy(amount = value)
    }

    fun setDate(value: String) {
        draft = draft.copy(date = value)
    }

    fun setMerchant(value: String) {
        draft = draft.copy(merchant = value)
    }

    fun setCurrencyCode(value: String) {
        draft = draft.copy(currencyCode = value)
    }

    fun toggleTag(tagId: String) {
        val updated = draft.selectedTagIds.toMutableSet().apply {
            if (!add(tagId)) remove(tagId)
        }
        draft = draft.copy(selectedTagIds = updated)
    }

    fun setTransferDestination(folderId: String) {
        draft = draft.copy(transferDestinationFolderId = folderId)
    }

    fun removeReceipt() {
        draft = draft.copy(receipt = null)
    }
}

private val transactionEntryStateSaver = listSaver<TransactionEntryState, Any>(
    save = { state ->
        val draft = state.draft
        listOf(
            draft.transactionId.orEmpty(),
            draft.kind.name,
            draft.amount,
            draft.date,
            draft.merchant,
            draft.currencyCode,
            ArrayList(draft.selectedTagIds),
            draft.transferDestinationFolderId.orEmpty(),
            draft.receipt?.uri.orEmpty(),
            draft.receipt?.displayName.orEmpty(),
        )
    },
    restore = { values ->
        val receiptUri = values[8] as String
        TransactionEntryState(
            TransactionEntryDraft(
                transactionId = (values[0] as String).ifEmpty { null },
                kind = TransactionKind.valueOf(values[1] as String),
                amount = values[2] as String,
                date = values[3] as String,
                merchant = values[4] as String,
                currencyCode = values[5] as String,
                selectedTagIds = (values[6] as ArrayList<*>).filterIsInstance<String>().toSet(),
                transferDestinationFolderId = (values[7] as String).ifEmpty { null },
                receipt = receiptUri.takeIf(String::isNotEmpty)?.let {
                    ReceiptAttachment(
                        uri = it,
                        displayName = (values[9] as String).ifEmpty { null },
                    )
                },
            ),
        )
    },
)

@Composable
fun rememberTransactionEntryState(
    initialDraft: TransactionEntryDraft,
    ocrSuggestion: OcrEntrySuggestion? = null,
): TransactionEntryState = rememberSaveable(
    initialDraft,
    ocrSuggestion,
    saver = transactionEntryStateSaver,
) {
    TransactionEntryState(initialDraft.withOcrSuggestion(ocrSuggestion))
}

/**
 * Minimal, persistence-agnostic entry UI. Tapping a folder validates and emits one normalized save
 * request; the caller owns database writes and navigation.
 */
@Composable
fun TransactionEntryScreen(
    config: TransactionEntryConfig,
    onSave: (ValidatedTransactionEntry) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val state = rememberTransactionEntryState(config.initialDraft, config.ocrSuggestion)
    TransactionEntryScreen(
        config = config,
        state = state,
        onSave = onSave,
        modifier = modifier,
        onCancel = onCancel,
    )
}

@Composable
fun TransactionEntryScreen(
    config: TransactionEntryConfig,
    state: TransactionEntryState,
    onSave: (ValidatedTransactionEntry) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    var errors by remember { mutableStateOf<Map<EntryField, String>>(emptyMap()) }
    val draft = state.draft

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (draft.transactionId == null) "New transaction" else "Edit transaction",
                style = MaterialTheme.typography.headlineSmall,
            )
            onCancel?.let { cancel -> TextButton(onClick = cancel) { Text("Cancel") } }
        }

        Text("Type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransactionKind.entries.forEach { kind ->
                FilterChip(
                    selected = draft.kind == kind,
                    onClick = {
                        state.setKind(kind)
                        errors = errors - EntryField.TRANSFER_DESTINATION
                    },
                    label = { Text(kind.displayName()) },
                )
            }
        }

        if (config.ocrSuggestion != null) {
            Text(
                text = "Receipt suggestions are editable. Check them before saving.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        EntryTextField(
            value = draft.amount,
            onValueChange = {
                state.setAmount(it)
                errors = errors - EntryField.AMOUNT
            },
            label = "Amount",
            error = errors[EntryField.AMOUNT],
            keyboardType = KeyboardType.Decimal,
        )
        EntryTextField(
            value = draft.currencyCode,
            onValueChange = {
                state.setCurrencyCode(it)
                errors = errors - EntryField.CURRENCY - EntryField.AMOUNT - EntryField.SAVE_FOLDER
            },
            label = "Currency (for example USD)",
            error = errors[EntryField.CURRENCY],
            keyboardType = KeyboardType.Text,
        )
        EntryTextField(
            value = draft.date,
            onValueChange = {
                state.setDate(it)
                errors = errors - EntryField.DATE
            },
            label = "Date (YYYY-MM-DD)",
            error = errors[EntryField.DATE],
            keyboardType = KeyboardType.Number,
        )
        EntryTextField(
            value = draft.merchant,
            onValueChange = {
                state.setMerchant(it)
                errors = errors - EntryField.MERCHANT
            },
            label = if (draft.kind == TransactionKind.INCOME) "Payer" else "Merchant",
            error = errors[EntryField.MERCHANT],
            keyboardType = KeyboardType.Text,
        )

        draft.receipt?.let { receipt ->
            ReceiptPreview(
                attachment = receipt,
                error = errors[EntryField.RECEIPT],
                onRemove = {
                    state.removeReceipt()
                    errors = errors - EntryField.RECEIPT
                },
            )
        }

        if (draft.kind == TransactionKind.TRANSFER) {
            Text("Transfer to")
            config.folders.forEach { folder ->
                FilterChip(
                    selected = draft.transferDestinationFolderId == folder.id,
                    onClick = {
                        state.setTransferDestination(folder.id)
                        errors = errors - EntryField.TRANSFER_DESTINATION
                    },
                    label = { Text("${folder.name} · ${folder.currencyCode.uppercase(Locale.ROOT)}") },
                )
            }
            FieldError(errors[EntryField.TRANSFER_DESTINATION])
        }

        Text("Tags (optional)")
        if (config.tags.isEmpty()) {
            Text("No tags available", style = MaterialTheme.typography.bodySmall)
        } else {
            config.tags.forEach { tag ->
                FilterChip(
                    selected = tag.id in draft.selectedTagIds,
                    onClick = {
                        state.toggleTag(tag.id)
                        errors = errors - EntryField.TAGS
                    },
                    label = { Text(tag.name) },
                )
            }
        }
        FieldError(errors[EntryField.TAGS])

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (draft.kind == TransactionKind.TRANSFER) {
                "Tap the source folder to save"
            } else {
                "Tap a folder to save"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (config.folders.isEmpty()) {
            Text("Create an account folder before saving.")
        } else {
            config.folders.forEach { folder ->
                Button(
                    onClick = {
                        when (
                            val result = TransactionEntryValidator.validate(
                                draft = state.draft,
                                tappedFolder = folder,
                                availableFolders = config.folders,
                                availableTags = config.tags,
                            )
                        ) {
                            is EntryValidationResult.Valid -> {
                                errors = emptyMap()
                                onSave(result.entry)
                            }
                            is EntryValidationResult.Invalid -> errors = result.errors
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${folder.name} · ${folder.currencyCode.uppercase(Locale.ROOT)}")
                }
            }
        }
        FieldError(errors[EntryField.SAVE_FOLDER])
    }
}

@Composable
private fun EntryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> ({ Text(message) }) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun FieldError(message: String?) {
    message?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReceiptPreview(
    attachment: ReceiptAttachment,
    error: String?,
    onRemove: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val image by produceState<ImageBitmap?>(initialValue = null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            decodeLocalPreview(resolver, Uri.parse(attachment.uri))
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Receipt", style = MaterialTheme.typography.titleMedium)
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = attachment.displayName ?: "Attached receipt",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                    contentScale = ContentScale.Fit,
                )
            } ?: Text(attachment.displayName ?: "Local receipt attached")
            FieldError(error)
            OutlinedButton(onClick = onRemove) { Text("Remove receipt") }
        }
    }
}

private fun decodeLocalPreview(contentResolver: ContentResolver, uri: Uri): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (largestSide / sampleSize > 1_600) sampleSize *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
    }
}.getOrNull()

private fun TransactionKind.displayName(): String = when (this) {
    TransactionKind.EXPENSE -> "Expense"
    TransactionKind.INCOME -> "Income"
    TransactionKind.TRANSFER -> "Transfer"
}
