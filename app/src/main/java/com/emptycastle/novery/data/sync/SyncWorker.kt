package com.emptycastle.novery.data.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.emptycastle.novery.data.local.PreferencesManager
import com.emptycastle.novery.service.NotificationHelper
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background worker for manual and automatic sync jobs.
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private val preferencesManager = PreferencesManager.getInstance(applicationContext)
    private val notifier = SyncNotifier(applicationContext)

    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString(KEY_TRIGGER)
                ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
                ?: SyncTrigger.AUTO

            if (trigger != SyncTrigger.MANUAL &&
                !SyncManager.shouldTriggerSync(preferencesManager, trigger)
            ) {
                return Result.success()
            }

            if (preferencesManager.getSyncSettings().showProgressNotifications) {
                setForeground(createForegroundInfo("Preparing sync"))
            }

            SyncManager(applicationContext).sync(trigger)
                .fold(
                    onSuccess = { message ->
                        if (preferencesManager.getSyncSettings().showProgressNotifications) {
                            notifier.showSuccess(message)
                        }
                        Result.success()
                    },
                    onFailure = { error ->
                        if (preferencesManager.getSyncSettings().showProgressNotifications) {
                            notifier.showError(error.message ?: "Sync failed.")
                        }
                        Result.failure()
                    }
                )
        } catch (_: CancellationException) {
            SyncStatusTracker.finishCancelled()
            if (preferencesManager.getSyncSettings().showProgressNotifications) {
                notifier.clear()
            }
            Result.success()
        } catch (error: Exception) {
            SyncStatusTracker.finishError(error.message ?: "Sync failed.")
            if (preferencesManager.getSyncSettings().showProgressNotifications) {
                notifier.showError(error.message ?: "Sync failed.")
            }
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Preparing sync")
    }

    private fun createForegroundInfo(stage: String): ForegroundInfo {
        val notification: Notification = notifier.showProgress(stage)
        return ForegroundInfo(
            NotificationHelper.NOTIFICATION_ID_SYNC,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "novery_sync_periodic"
        const val UNIQUE_IMMEDIATE_WORK = "novery_sync_immediate"
        private const val TAG_SYNC = "novery_sync"
        private const val KEY_TRIGGER = "trigger"

        fun isRunning(context: Context): Boolean {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosByTag(TAG_SYNC).get()
            return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

        fun schedule(context: Context) {
            val prefs = PreferencesManager.getInstance(context)
            val settings = prefs.getSyncSettings()
            val workManager = WorkManager.getInstance(context)

            if (settings.service == SyncServiceType.NONE ||
                settings.intervalMinutes <= 0 ||
                !settings.googleDriveSignedIn
            ) {
                workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
                return
            }

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                settings.intervalMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.AUTO.name))
                .setConstraints(syncConstraints())
                .addTag(TAG_SYNC)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun triggerNow(context: Context, trigger: SyncTrigger) {
            val prefs = PreferencesManager.getInstance(context)
            if (!SyncManager.shouldTriggerSync(prefs, trigger)) {
                return
            }

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                .setConstraints(syncConstraints())
                .addTag(TAG_SYNC)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
        }

        private fun syncConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }
    }
}
