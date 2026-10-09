package dev.quinntyx.charon.capture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importCopiesImageIntoDurableMimeTypedFile() {
        val root = temporaryFolder.newFolder("receipts")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val store = ReceiptImageStore(root, idFactory = { "receipt_1" })

        val image = store.importImage(ByteArrayInputStream(bytes), "image/png")

        assertEquals("receipt_1.png", image.file.name)
        assertEquals(ReceiptImageSource.IMPORT, image.source)
        assertArrayEquals(bytes, image.file.readBytes())
        assertFalse(root.resolve(".receipt_1.png.part").exists())
    }

    @Test
    fun failedImportLeavesNoCompletedOrStagingFile() {
        val root = temporaryFolder.newFolder("failed")
        val store = ReceiptImageStore(root, idFactory = { "broken" })
        val failingInput = object : InputStream() {
            private var reads = 0
            override fun read(): Int = if (reads++ == 0) 42 else throw IOException("source failed")
        }

        assertThrows(IOException::class.java) {
            store.importImage(failingInput, "image/jpeg")
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun oversizedImportIsRejectedAndCleanedUp() {
        val root = temporaryFolder.newFolder("bounded")
        val store = ReceiptImageStore(root, idFactory = { "large" }, maxImportBytes = 3)

        assertThrows(ReceiptImageTooLargeException::class.java) {
            store.importImage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), "image/jpeg")
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cameraFileIsNotPublishedUntilCommit() {
        val root = temporaryFolder.newFolder("camera")
        val store = ReceiptImageStore(root, idFactory = { "camera-shot" })

        val pending = store.beginCameraCapture()
        pending.stagingFile.writeBytes(byteArrayOf(9, 8, 7))
        assertFalse(root.resolve("camera-shot.jpg").exists())

        val image = store.commitCameraCapture(pending)

        assertEquals(ReceiptImageSource.CAMERA, image.source)
        assertTrue(image.file.isFile)
        assertArrayEquals(byteArrayOf(9, 8, 7), image.file.readBytes())
        assertFalse(pending.stagingFile.exists())
    }

    @Test
    fun emptyCameraOutputIsRejectedAndRemoved() {
        val root = temporaryFolder.newFolder("empty-camera")
        val store = ReceiptImageStore(root, idFactory = { "empty" })
        val pending = store.beginCameraCapture()
        pending.stagingFile.createNewFile()

        assertThrows(IOException::class.java) { store.commitCameraCapture(pending) }

        assertFalse(pending.stagingFile.exists())
        assertFalse(root.resolve("empty.jpg").exists())
    }

    @Test
    fun unsafeGeneratedIdCannotEscapeReceiptDirectory() {
        val root = temporaryFolder.newFolder("safe")
        val store = ReceiptImageStore(root, idFactory = { "../escape" })

        assertThrows(IllegalArgumentException::class.java) { store.beginCameraCapture() }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun knownImageMimeTypesKeepUsefulExtensions() {
        assertEquals("png", ReceiptImageStore.extensionForMimeType("IMAGE/PNG"))
        assertEquals("webp", ReceiptImageStore.extensionForMimeType("image/webp"))
        assertEquals("heic", ReceiptImageStore.extensionForMimeType("image/heif"))
        assertEquals("jpg", ReceiptImageStore.extensionForMimeType(null))
        assertEquals("jpg", ReceiptImageStore.extensionForMimeType("image/unknown"))
    }
}
