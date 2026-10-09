package dev.quinntyx.charon.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupRestoreCoordinatorTest {
    private val archive = CharonBackupArchive()
    private val coordinator = BackupRestoreCoordinator(archive)

    @Test
    fun `restore passes explicit duplicate policy streams receipts and commits once`() = runTest {
        val target = RecordingTarget()
        val bytes = validArchive()

        val result = coordinator.restore(
            openInput = { ByteArrayInputStream(bytes) },
            target = target,
            duplicatePolicy = DuplicatePolicy.REPLACE_EXISTING,
        )

        assertEquals(DuplicatePolicy.REPLACE_EXISTING, target.policy)
        assertEquals(1, target.appliedData?.records?.size)
        assertArrayEquals(byteArrayOf(9, 8, 7), target.receipts.getValue("r-1"))
        assertTrue(target.committed)
        assertFalse(target.rolledBack)
        assertEquals(1, result.recordCount)
        assertEquals(1, result.receiptCount)
    }

    @Test
    fun `apply failure rolls back and keeps original failure`() {
        val expected = IllegalStateException("database rejected record")
        val target = RecordingTarget(applyFailure = expected)
        val bytes = validArchive()

        val actual = assertThrows(IllegalStateException::class.java) {
            runTest {
                coordinator.restore(
                    openInput = { ByteArrayInputStream(bytes) },
                    target = target,
                    duplicatePolicy = DuplicatePolicy.KEEP_EXISTING,
                )
            }
        }

        assertSame(expected, actual)
        assertTrue(target.rolledBack)
        assertFalse(target.committed)
    }

    @Test
    fun `commit failure also requests rollback`() {
        val target = RecordingTarget(commitFailure = IllegalStateException("receipt rename failed"))
        val bytes = validArchive()

        assertThrows(IllegalStateException::class.java) {
            runTest {
                coordinator.restore(
                    openInput = { ByteArrayInputStream(bytes) },
                    target = target,
                    duplicatePolicy = DuplicatePolicy.KEEP_EXISTING,
                )
            }
        }

        assertTrue(target.rolledBack)
    }

    @Test
    fun `job cancellation still completes suspending rollback`() = runTest {
        val applyStarted = CompletableDeferred<Unit>()
        var rollbackContextWasActive = false
        var rollbackFinished = false
        val target = TransactionalRestoreTarget {
            object : RestoreSession {
                override suspend fun applyData(data: BackupData, duplicatePolicy: DuplicatePolicy) {
                    applyStarted.complete(Unit)
                    awaitCancellation()
                }

                override suspend fun applyReceipt(receipt: BackupReceiptMetadata, content: java.io.InputStream) = Unit

                override suspend fun commit() = Unit

                override suspend fun rollback() {
                    rollbackContextWasActive = currentCoroutineContext().isActive
                    delay(1)
                    rollbackFinished = true
                }
            }
        }
        val bytes = validArchive()
        val restoreJob = launch {
            coordinator.restore(
                openInput = { ByteArrayInputStream(bytes) },
                target = target,
                duplicatePolicy = DuplicatePolicy.KEEP_EXISTING,
            )
        }

        applyStarted.await()
        restoreJob.cancelAndJoin()

        assertTrue(restoreJob.isCancelled)
        assertTrue(rollbackContextWasActive)
        assertTrue(rollbackFinished)
    }

    @Test
    fun `rollback failure is suppressed on the original failure`() {
        val applyFailure = IllegalStateException("database rejected record")
        val rollbackFailure = IllegalStateException("rollback failed")
        val target = RecordingTarget(
            applyFailure = applyFailure,
            rollbackFailure = rollbackFailure,
        )
        val bytes = validArchive()

        val actual = assertThrows(IllegalStateException::class.java) {
            runTest {
                coordinator.restore(
                    openInput = { ByteArrayInputStream(bytes) },
                    target = target,
                    duplicatePolicy = DuplicatePolicy.KEEP_EXISTING,
                )
            }
        }

        assertSame(applyFailure, actual)
        assertEquals(1, actual.suppressed.size)
        assertEquals(rollbackFailure::class, actual.suppressed.single()::class)
        assertEquals(rollbackFailure.message, actual.suppressed.single().message)
    }

    @Test
    fun `document changed after validation rolls back staged data`() {
        val valid = validArchive()
        val changed = rewriteZip(valid) { name, content ->
            if (name.startsWith("receipts/")) content + 1 else content
        }
        val target = RecordingTarget()
        var openCount = 0

        val error = assertThrows(InvalidBackupException::class.java) {
            runTest {
                coordinator.restore(
                    openInput = {
                        openCount += 1
                        ByteArrayInputStream(if (openCount == 1) valid else changed)
                    },
                    target = target,
                    duplicatePolicy = DuplicatePolicy.KEEP_EXISTING,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("changed"))
        assertTrue(target.began)
        assertTrue(target.rolledBack)
        assertFalse(target.committed)
    }

    @Test
    fun `corrupt archive is rejected before restore transaction begins`() {
        val target = RecordingTarget()

        assertThrows(InvalidBackupException::class.java) {
            runTest {
                coordinator.restore(
                    openInput = { ByteArrayInputStream("not a zip".toByteArray()) },
                    target = target,
                    duplicatePolicy = DuplicatePolicy.KEEP_EXISTING,
                )
            }
        }

        assertFalse(target.began)
        assertFalse(target.rolledBack)
    }

    private fun validArchive(): ByteArray {
        val snapshot = BackupSnapshot(
            createdAtEpochMillis = 42,
            records = listOf(BackupRecord("transactions", "tx-1", "{\"minorUnits\":100}")),
            receipts = listOf(BackupReceipt("r-1", "image/jpeg", byteArrayOf(9, 8, 7))),
        )
        return ByteArrayOutputStream().also { archive.write(snapshot, it) }.toByteArray()
    }

    private fun rewriteZip(
        source: ByteArray,
        transform: (String, ByteArray) -> ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { destination ->
            ZipInputStream(ByteArrayInputStream(source)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(transform(entry.name, input.readBytes()))
                    destination.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private class RecordingTarget(
        private val applyFailure: Throwable? = null,
        private val commitFailure: Throwable? = null,
        private val rollbackFailure: Throwable? = null,
    ) : TransactionalRestoreTarget {
        var began = false
        var appliedData: BackupData? = null
        var policy: DuplicatePolicy? = null
        val receipts = linkedMapOf<String, ByteArray>()
        var committed = false
        var rolledBack = false

        override suspend fun beginRestore(): RestoreSession {
            began = true
            return object : RestoreSession {
                override suspend fun applyData(data: BackupData, duplicatePolicy: DuplicatePolicy) {
                    appliedData = data
                    policy = duplicatePolicy
                    applyFailure?.let { throw it }
                }

                override suspend fun applyReceipt(receipt: BackupReceiptMetadata, content: java.io.InputStream) {
                    receipts[receipt.stableId] = content.readBytes()
                }

                override suspend fun commit() {
                    commitFailure?.let { throw it }
                    committed = true
                }

                override suspend fun rollback() {
                    rolledBack = true
                    rollbackFailure?.let { throw it }
                }
            }
        }
    }
}
