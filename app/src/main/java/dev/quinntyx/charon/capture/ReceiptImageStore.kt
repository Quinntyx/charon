package dev.quinntyx.charon.capture

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A durable, app-private receipt image ready for OCR or transaction attachment. */
data class ReceiptImage(
    val id: String,
    val file: File,
    val source: ReceiptImageSource,
)

enum class ReceiptImageSource { CAMERA, IMPORT }

/** A camera destination that is invisible to consumers until [ReceiptImageStore.commitCameraCapture]. */
data class PendingReceiptImage internal constructor(
    val id: String,
    val stagingFile: File,
    internal val finalFile: File,
)

/**
 * Owns receipt image files under a single app-private directory.
 *
 * Imports are bounded and both import and camera writes use a staging file followed by a rename, so
 * interrupted work is never exposed as a completed receipt. Call blocking methods from a worker
 * dispatcher.
 */
class ReceiptImageStore(
    private val directory: File,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val maxImportBytes: Long = DEFAULT_MAX_IMPORT_BYTES,
) {
    init {
        require(maxImportBytes > 0) { "maxImportBytes must be positive" }
    }

    fun importImage(input: InputStream, mimeType: String?): ReceiptImage {
        ensureDirectory()
        val id = safeId(idFactory())
        val extension = extensionForMimeType(mimeType)
        val finalFile = File(directory, "$id.$extension")
        val stagingFile = File(directory, ".$id.$extension.part")
        checkDestinationAvailable(finalFile, stagingFile)

        try {
            FileOutputStream(stagingFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxImportBytes) {
                        throw ReceiptImageTooLargeException(maxImportBytes)
                    }
                    output.write(buffer, 0, read)
                }
                if (total == 0L) throw IOException("The selected image is empty")
                output.fd.sync()
            }
            moveIntoPlace(stagingFile, finalFile)
            return ReceiptImage(id, finalFile, ReceiptImageSource.IMPORT)
        } catch (failure: Throwable) {
            stagingFile.delete()
            throw failure
        }
    }

    fun beginCameraCapture(): PendingReceiptImage {
        ensureDirectory()
        val id = safeId(idFactory())
        val finalFile = File(directory, "$id.jpg")
        val stagingFile = File(directory, ".$id.jpg.part")
        checkDestinationAvailable(finalFile, stagingFile)
        return PendingReceiptImage(id, stagingFile, finalFile)
    }

    fun commitCameraCapture(pending: PendingReceiptImage): ReceiptImage {
        require(pending.finalFile.parentFile?.canonicalFile == directory.canonicalFile) {
            "Pending image does not belong to this store"
        }
        if (!pending.stagingFile.isFile || pending.stagingFile.length() == 0L) {
            pending.stagingFile.delete()
            throw IOException("Camera did not produce an image")
        }
        moveIntoPlace(pending.stagingFile, pending.finalFile)
        return ReceiptImage(pending.id, pending.finalFile, ReceiptImageSource.CAMERA)
    }

    fun discardCameraCapture(pending: PendingReceiptImage) {
        pending.stagingFile.delete()
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create receipt image directory")
        }
        if (!directory.isDirectory) throw IOException("Receipt image path is not a directory")
    }

    private fun checkDestinationAvailable(finalFile: File, stagingFile: File) {
        if (finalFile.exists() || stagingFile.exists()) {
            throw IOException("Receipt image id already exists")
        }
    }

    private fun moveIntoPlace(stagingFile: File, finalFile: File) {
        try {
            Files.move(
                stagingFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(stagingFile.toPath(), finalFile.toPath())
        }
    }

    private fun safeId(candidate: String): String {
        require(SAFE_ID.matches(candidate)) { "Receipt image id contains unsafe characters" }
        return candidate
    }

    companion object {
        const val DEFAULT_MAX_IMPORT_BYTES = 25L * 1024L * 1024L
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")

        fun extensionForMimeType(mimeType: String?): String = when (mimeType?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    }
}

class ReceiptImageTooLargeException(maxBytes: Long) :
    IOException("The selected image exceeds the ${maxBytes / (1024 * 1024)} MiB limit")
