package dev.quinntyx.charon.capture

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptCaptureCompletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cameraCommitFinishesAfterScreenScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val screenScope = CoroutineScope(Job() + dispatcher)
        val root = temporaryFolder.newFolder("commit-after-navigation")
        val store = ReceiptImageStore(root, idFactory = { "receipt" })
        val pending = store.beginCameraCapture().also {
            it.stagingFile.writeBytes(byteArrayOf(4, 2))
        }
        val completion = ReceiptCaptureCompletion(store, workerScope, dispatcher)
        var result: Result<ReceiptImage>? = null

        val completionJob = completion.commit(pending) { result = it }
        screenScope.cancel() // Simulates switching away from ReceiptCaptureScreen.
        advanceUntilIdle()

        assertTrue(completionJob.isCompleted)
        val image = checkNotNull(result).getOrThrow()
        assertTrue(image.file.isFile)
        assertArrayEquals(byteArrayOf(4, 2), image.file.readBytes())
        assertFalse(pending.stagingFile.exists())
        workerScope.cancel()
    }

    @Test
    fun failedCameraOutputIsCleanedAfterScreenScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val screenScope = CoroutineScope(Job() + dispatcher)
        val root = temporaryFolder.newFolder("failure-after-navigation")
        val store = ReceiptImageStore(root, idFactory = { "empty" })
        val pending = store.beginCameraCapture().also { it.stagingFile.createNewFile() }
        val completion = ReceiptCaptureCompletion(store, workerScope, dispatcher)
        var result: Result<ReceiptImage>? = null

        completion.commit(pending) { result = it }
        screenScope.cancel()
        advanceUntilIdle()

        assertTrue(checkNotNull(result).exceptionOrNull() is IOException)
        assertFalse(pending.stagingFile.exists())
        assertFalse(root.resolve("empty.jpg").exists())
        workerScope.cancel()
    }

    @Test
    fun cameraErrorDiscardFinishesAfterScreenScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val screenScope = CoroutineScope(Job() + dispatcher)
        val root = temporaryFolder.newFolder("discard-after-navigation")
        val store = ReceiptImageStore(root, idFactory = { "cancelled" })
        val pending = store.beginCameraCapture().also {
            it.stagingFile.writeBytes(byteArrayOf(1))
        }
        val completion = ReceiptCaptureCompletion(store, workerScope, dispatcher)
        var callbackRan = false

        completion.discard(pending) { callbackRan = true }
        screenScope.cancel()
        advanceUntilIdle()

        assertTrue(callbackRan)
        assertFalse(pending.stagingFile.exists())
        workerScope.cancel()
    }
}
