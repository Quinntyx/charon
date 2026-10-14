package dev.quinntyx.charon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.common.InputImage
import dev.quinntyx.charon.capture.ReceiptCaptureScreen
import dev.quinntyx.charon.capture.ReceiptImage
import dev.quinntyx.charon.dashboard.DashboardRoute
import dev.quinntyx.charon.dashboard.DashboardViewModel
import dev.quinntyx.charon.entry.OcrEntrySuggestion
import dev.quinntyx.charon.entry.ReceiptAttachment
import dev.quinntyx.charon.entry.TransactionEntryDraft
import dev.quinntyx.charon.entry.formatEditableAmount
import dev.quinntyx.charon.receipt.ocr.BundledMlKitReceiptRecognizer
import dev.quinntyx.charon.receipt.ocr.ReceiptParser

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    CharonRoot(application as CharonApplication)
                }
            }
        }
    }
}

private enum class AppScreen(val label: String) {
    OVERVIEW("Overview"), RECEIPT("Receipt"), ENTRY("Add"), ORGANIZE("Folders"),
    RECURRING("Recurring"), BACKUP("Backup"),
}

@Composable
private fun CharonRoot(app: CharonApplication) {
    var screenName by rememberSaveable { mutableStateOf(AppScreen.OVERVIEW.name) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var receiptImage by remember { mutableStateOf<ReceiptImage?>(null) }
    val screen = AppScreen.valueOf(screenName)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppScreen.entries.forEach { destination ->
                Button(onClick = {
                    editingId = null
                    receiptImage = null
                    screenName = destination.name
                }) { Text(destination.label) }
            }
        }
        when (screen) {
            AppScreen.OVERVIEW -> {
                val model: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory(app.dashboardRepository))
                DashboardRoute(
                    viewModel = model,
                    onAddTransaction = { editingId = null; screenName = AppScreen.ENTRY.name },
                    onEditTransaction = { id -> editingId = id.toLongOrNull(); screenName = AppScreen.ENTRY.name },
                    modifier = Modifier.weight(1f),
                )
            }
            AppScreen.ENTRY -> TransactionEntryHost(
                adapter = app.transactionEntryAdapter,
                transactionId = editingId,
                onFinished = { editingId = null; screenName = AppScreen.OVERVIEW.name },
            )
            AppScreen.RECEIPT -> {
                val image = receiptImage
                if (image == null) {
                    ReceiptCaptureScreen(onReceiptReady = { receiptImage = it })
                } else {
                    ReceiptOcrEntry(
                        app = app,
                        image = image,
                        onCancel = { receiptImage = null },
                        onFinished = { receiptImage = null; screenName = AppScreen.OVERVIEW.name },
                    )
                }
            }
            AppScreen.ORGANIZE -> OrganizationScreen(app.ledgerRepository)
            AppScreen.RECURRING -> RecurrenceScreen(app.recurrenceService, app.ledgerRepository)
            AppScreen.BACKUP -> BackupScreen(app)
        }
    }
}

@Composable
private fun ReceiptOcrEntry(
    app: CharonApplication,
    image: ReceiptImage,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val recognizer = remember { BundledMlKitReceiptRecognizer() }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }
    val result by produceState<Result<OcrEntrySuggestion>?>(null, image.file.absolutePath) {
        value = runCatching {
            val parsed = ReceiptParser().parse(
                recognizer.recognize(InputImage.fromFilePath(context, image.file.toUri())),
            )
            val currency = parsed.currency?.value?.value
            OcrEntrySuggestion(
                amount = if (currency != null && parsed.total != null) {
                    formatEditableAmount(parsed.total.value.minorUnits, currency)
                } else null,
                date = parsed.date?.value?.toString(),
                merchant = parsed.merchant?.value,
                currencyCode = currency,
            )
        }
    }
    when (val state = result) {
        null -> Text("Reading receipt on device…", modifier = Modifier.padding(24.dp))
        else -> {
            val suggestion = state.getOrElse { OcrEntrySuggestion() }
            Column(Modifier.fillMaxSize()) {
                state.exceptionOrNull()?.let {
                    Text("OCR could not read this image; enter the details manually.", modifier = Modifier.padding(12.dp))
                }
                TransactionEntryHost(
                    adapter = app.transactionEntryAdapter,
                    transactionId = null,
                    initialDraft = TransactionEntryDraft(
                        date = suggestion.date.orEmpty(),
                        merchant = suggestion.merchant.orEmpty(),
                        amount = suggestion.amount.orEmpty(),
                        currencyCode = suggestion.currencyCode.orEmpty(),
                        receipt = ReceiptAttachment(image.file.toUri().toString(), image.file.name),
                    ),
                    onFinished = onFinished,
                )
                Button(onClick = onCancel, modifier = Modifier.padding(8.dp)) { Text("Discard and retake") }
            }
        }
    }
}
