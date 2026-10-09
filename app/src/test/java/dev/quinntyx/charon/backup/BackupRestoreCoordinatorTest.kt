package dev.quinntyx.charon.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupRestoreCoordinatorTest {
    private val archive = CharonBackupArchive()
    private val coordinator = BackupRestoreCoordinator(archive)

    @Test
    fun `restore passes explicit duplicate policy and commits once`() = runTest {
        val target = RecordingTarget()

        val result = coordinator.restore(
            ByteArrayInputStream(validArchive()),
            target,
            DuplicatePolicy.REPLACE_EXISTING,
        )

        assertEquals(DuplicatePolicy.REPLACE_EXISTING, target.policy)
        assertEquals(1, target.appliedSnapshot?.records?.size)
        assertTrue(target.committed)
        assertFalse(target.rolledBack)
        assertEquals(1, result.recordCount)
        assertEquals(1, result.receiptCount)
    }

    @Test
    fun `apply failure rolls back and keeps original failure`() {
        val expected = IllegalStateException("database rejected record")
        val target = RecordingTarget(applyFailure = expected)

        val actual = assertThrows(IllegalStateException::class.java) {
            runTest {
                coordinator.restore(
                    ByteArrayInputStream(validArchive()),
                    target,
                    DuplicatePolicy.KEEP_EXISTING,
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

        assertThrows(IllegalStateException::class.java) {
            runTest {
                coordinator.restore(
                    ByteArrayInputStream(validArchive()),
                    target,
                    DuplicatePolicy.KEEP_EXISTING,
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
                override suspend fun apply(snapshot: BackupSnapshot, duplicatePolicy: DuplicatePolicy) {
                    applyStarted.complete(Unit)
                    awaitCancellation()
                }

                override suspend fun commit() = Unit

                override suspend fun rollback() {
                    rollbackContextWasActive = currentCoroutineContext().isActive
                    delay(1)
                    rollbackFinished = true
                }
            }
        }
        val restoreJob = launch {
            coordinator.restore(
                ByteArrayInputStream(validArchive()),
                target,
                DuplicatePolicy.KEEP_EXISTING,
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

        val actual = assertThrows(IllegalStateException::class.java) {
            runTest {
                coordinator.restore(
                    ByteArrayInputStream(validArchive()),
                    target,
                    DuplicatePolicy.KEEP_EXISTING,
                )
            }
        }

        assertSame(applyFailure, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(rollbackFailure, actual.suppressed.single())
    }

    @Test
    fun `corrupt archive is rejected before restore transaction begins`() {
        val target = RecordingTarget()

        assertThrows(InvalidBackupException::class.java) {
            runTest {
                coordinator.restore(
                    ByteArrayInputStream("not a zip".toByteArray()),
                    target,
                    DuplicatePolicy.KEEP_EXISTING,
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

    private class RecordingTarget(
        private val applyFailure: Throwable? = null,
        private val commitFailure: Throwable? = null,
        private val rollbackFailure: Throwable? = null,
    ) : TransactionalRestoreTarget {
        var began = false
        var appliedSnapshot: BackupSnapshot? = null
        var policy: DuplicatePolicy? = null
        var committed = false
        var rolledBack = false

        override suspend fun beginRestore(): RestoreSession {
            began = true
            return object : RestoreSession {
                override suspend fun apply(snapshot: BackupSnapshot, duplicatePolicy: DuplicatePolicy) {
                    appliedSnapshot = snapshot
                    policy = duplicatePolicy
                    applyFailure?.let { throw it }
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
