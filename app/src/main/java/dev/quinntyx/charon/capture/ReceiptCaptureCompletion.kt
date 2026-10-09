package dev.quinntyx.charon.capture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Finishes camera file operations independently of the capture screen's composition lifetime.
 *
 * CameraX can report success or failure after navigation disposes the screen. Work therefore runs
 * in a process-lifetime scope rather than a rememberCoroutineScope, while callbacks return to the
 * main dispatcher for Compose state and downstream navigation.
 */
internal class ReceiptCaptureCompletion(
    private val store: ReceiptImageStore,
    private val workerScope: CoroutineScope = ReceiptCaptureCompletionWorker.scope,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    fun commit(
        pending: PendingReceiptImage,
        onComplete: (Result<ReceiptImage>) -> Unit,
    ): Job = workerScope.launch {
        val result = runCatching { store.commitCameraCapture(pending) }
        withContext(callbackDispatcher) { onComplete(result) }
    }

    fun discard(
        pending: PendingReceiptImage,
        onComplete: () -> Unit,
    ): Job = workerScope.launch {
        try {
            store.discardCameraCapture(pending)
        } finally {
            withContext(callbackDispatcher) { onComplete() }
        }
    }
}

private object ReceiptCaptureCompletionWorker {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
