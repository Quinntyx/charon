package dev.quinntyx.charon.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CharonBackupArchiveTest {
    private val archive = CharonBackupArchive()

    @Test
    fun `round trip preserves logical records and original receipt bytes`() {
        val original = BackupSnapshot(
            createdAtEpochMillis = 1_735_689_600_123,
            records = listOf(
                BackupRecord("transactions", "tx-2", "{\"minorUnits\":1299,\"currency\":\"EUR\"}"),
                BackupRecord("folders", "cash", "{\"name\":\"Café wallet\"}"),
            ),
            receipts = listOf(
                BackupReceipt("receipt/with a source ID", "image/jpeg", byteArrayOf(0, 1, 2, -1)),
                BackupReceipt("scan-2", "image/png", "not actually a png in this unit test".toByteArray()),
            ),
        )

        val restored = archive.read(ByteArrayInputStream(write(original)))

        assertEquals(original.createdAtEpochMillis, restored.createdAtEpochMillis)
        assertEquals(
            original.records.associate { it.collection to JSONObject(it.payloadJson).toString() },
            restored.records.associate { it.collection to it.payloadJson },
        )
        assertEquals(original.receipts.map { it.stableId }, restored.receipts.map { it.stableId })
        restored.receipts.forEach { restoredReceipt ->
            val expected = original.receipts.single { it.stableId == restoredReceipt.stableId }
            assertEquals(expected.mimeType, restoredReceipt.mimeType)
            assertArrayEquals(expected.bytes, restoredReceipt.bytes)
        }
    }

    @Test
    fun `changed receipt content is rejected by checksum validation`() {
        val bytes = write(sampleSnapshot())
        val changed = rewriteZip(bytes) { name, content ->
            if (name.startsWith("receipts/")) content + 99 else content
        }

        val error = assertThrows(InvalidBackupException::class.java) {
            archive.read(ByteArrayInputStream(changed))
        }

        assertTrue(error.message.orEmpty().contains("mismatch"))
    }

    @Test
    fun `unsupported version is reported distinctly`() {
        val changed = rewriteZip(write(sampleSnapshot())) { name, content ->
            if (name == "manifest.json") {
                JSONObject(content.toString(Charsets.UTF_8))
                    .put("version", 999)
                    .toString()
                    .toByteArray()
            } else {
                content
            }
        }

        assertThrows(UnsupportedBackupVersionException::class.java) {
            archive.read(ByteArrayInputStream(changed))
        }
    }

    @Test
    fun `path traversal entry is rejected before extraction or restore`() {
        val malicious = zipOf(
            "manifest.json" to "{}".toByteArray(),
            "receipts/../../outside.jpg" to byteArrayOf(1, 2, 3),
        )

        val error = assertThrows(InvalidBackupException::class.java) {
            archive.read(ByteArrayInputStream(malicious))
        }

        assertTrue(error.message.orEmpty().contains("Unsafe ZIP entry path"))
    }

    @Test
    fun `duplicate stable IDs are rejected during export`() {
        val duplicate = sampleSnapshot().copy(
            records = listOf(
                BackupRecord("transactions", "same", "{\"value\":1}"),
                BackupRecord("transactions", "same", "{\"value\":2}"),
            ),
        )

        assertThrows(InvalidBackupException::class.java) { write(duplicate) }
    }

    @Test
    fun `entry size limit applies to decompressed content`() {
        val limited = CharonBackupArchive(
            CharonBackupArchive.Limits(
                maxEntryBytes = 32,
                maxTotalBytes = 1_024,
                maxEntries = 10,
                maxRecords = 10,
                maxReceipts = 10,
            ),
        )
        val compressedLargeEntry = zipOf("manifest.json" to ByteArray(100) { 0 })

        val error = assertThrows(InvalidBackupException::class.java) {
            limited.read(ByteArrayInputStream(compressedLargeEntry))
        }

        assertTrue(error.message.orEmpty().contains("size limit"))
    }

    private fun sampleSnapshot() = BackupSnapshot(
        createdAtEpochMillis = 1234,
        records = listOf(BackupRecord("transactions", "tx-1", "{\"minorUnits\":2500}")),
        receipts = listOf(BackupReceipt("receipt-1", "image/jpeg", byteArrayOf(5, 4, 3, 2, 1))),
    )

    private fun write(snapshot: BackupSnapshot): ByteArray =
        ByteArrayOutputStream().also { archive.write(snapshot, it) }.toByteArray()

    private fun rewriteZip(
        source: ByteArray,
        transform: (String, ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(source)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries += entry.name to transform(entry.name, input.readBytes())
            }
        }
        return zipOf(*entries.toTypedArray())
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
