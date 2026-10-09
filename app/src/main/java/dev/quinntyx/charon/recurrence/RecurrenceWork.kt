package dev.quinntyx.charon.recurrence

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Implement this on the host Application after binding the production recurrence repository. */
interface RecurrenceWorkerDependencies {
    val recurrenceService: RecurrenceService
    val recurrenceClock: Clock
        get() = Clock.systemDefaultZone()
}

class RecurrenceCatchUpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dependencies = applicationContext as? RecurrenceWorkerDependencies
            ?: return Result.failure()
        return try {
            dependencies.recurrenceService.catchUp(LocalDate.now(dependencies.recurrenceClock))
            Result.success()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/**
 * Enqueues opportunistic daily catch-up. Android chooses the actual run time; this deliberately makes
 * no exact-time or same-day execution claim.
 */
object RecurrenceWorkScheduler {
    private const val UNIQUE_WORK_NAME = "recurring-payment-catch-up"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecurrenceCatchUpWorker>(
            24,
            TimeUnit.HOURS,
            6,
            TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .build(),
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
