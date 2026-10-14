package dev.quinntyx.charon.receipt.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs the bundled Latin ML Kit model entirely on-device.
 *
 * Callers create [InputImage] from their camera or imported image and must close this object when
 * its screen or owning component is destroyed. The bundled `text-recognition` artifact is used,
 * rather than the Play Services artifact that can require a model download.
 */
class BundledMlKitReceiptRecognizer(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
) : Closeable {

    suspend fun recognize(image: InputImage): ReceiptText {
        val recognized = suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    if (continuation.isActive) continuation.resume(text)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCanceledListener { continuation.cancel() }
        }
        return recognized.toReceiptText()
    }

    override fun close() {
        recognizer.close()
    }
}

private fun Text.toReceiptText(): ReceiptText {
    val receiptLines = textBlocks.flatMapIndexed { blockIndex, block ->
        block.lines.mapIndexed { lineIndex, line ->
            ReceiptTextLine(
                text = line.text,
                blockIndex = blockIndex,
                lineIndex = lineIndex,
            )
        }
    }
    return ReceiptText(rawText = this.text, lines = receiptLines)
}
