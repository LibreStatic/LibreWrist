package io.openshm.patcher.update

import android.app.job.JobScheduler
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.MainActivity
import io.openshm.patcher.R
import io.openshm.patcher.data.AppUpdate
import io.openshm.patcher.data.AppUpdateChecker
import io.openshm.patcher.data.AppUpdatePrefs
import io.openshm.patcher.data.AppUpdateResult
import io.openshm.patcher.data.AppUpdater
import io.openshm.patcher.data.BackgroundOutcome
import io.openshm.patcher.data.HttpsCatalogueFetcher
import io.openshm.patcher.data.UpdateJobPort
import io.openshm.patcher.data.syncUpdateJob
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The checker the app uses everywhere: the release API of this build's repository. */
internal fun newAppUpdateChecker(context: Context, settings: AppUpdatePrefs = AppUpdatePrefs(context)): AppUpdateChecker {
    val fetcher = HttpsCatalogueFetcher(
        mapOf("User-Agent" to "LibreWrist/${BuildConfig.VERSION_NAME}", "Accept" to "application/vnd.github+json"),
    )
    return AppUpdateChecker(
        BuildConfig.UPDATE_REPO,
        settings,
        { AppUpdater(BuildConfig.UPDATE_REPO, BuildConfig.VERSION_CODE.toLong(), fetcher).check() },
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )
}

/** Keeps the periodic update job in line with the "check automatically" switch. */
object AppUpdateScheduler {
    private const val JOB_ID = 0x5E1F
    private val PERIOD_MS = TimeUnit.HOURS.toMillis(12)
    private val FLEX_MS = TimeUnit.HOURS.toMillis(3)

    /** Schedules or cancels the job as the switch and this build's repository dictate. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val port = object : UpdateJobPort {
            override fun isScheduled() = scheduler.getPendingJob(JOB_ID) != null
            override fun schedule() {
                val job = JobInfo.Builder(JOB_ID, ComponentName(app, AppUpdateJobService::class.java))
                    .setPeriodic(PERIOD_MS, FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            }
            override fun cancel() = scheduler.cancel(JOB_ID)
        }
        syncUpdateJob(AppUpdatePrefs(app).autoCheck, BuildConfig.UPDATE_REPO, port)
    }
}

/** The "a newer build is ready" notification. */
object AppUpdateNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 0x5E1F

    /** Posts the notification unless notifications are off (no permission prompt here). True when posted. */
    fun post(context: Context, update: AppUpdate): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.update_channel_description) },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_UPDATER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, update.build, update.versionName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** A tap on the notification asks the app to open the updater; consumed once by the update view model. */
object AppUpdateRequests {
    private val _openUpdater = MutableStateFlow(false)
    val openUpdater: StateFlow<Boolean> = _openUpdater

    fun request() {
        _openUpdater.value = true
    }

    fun consume() {
        _openUpdater.value = false
    }
}

/** Runs the 12-hourly check off the main thread and notifies about a build not announced yet. */
class AppUpdateJobService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        worker = Thread {
            val retry = try {
                val settings = AppUpdatePrefs(app)
                val checker = newAppUpdateChecker(app, settings)
                when (val outcome = checker.checkInBackground()) {
                    BackgroundOutcome.Done -> false
                    BackgroundOutcome.Retry -> true
                    is BackgroundOutcome.Notify -> {
                        if (AppUpdateNotifier.post(app, outcome.update)) checker.markNotified(outcome.update.build)
                        false
                    }
                }
            } catch (e: RuntimeException) {
                true
            }
            jobFinished(params, retry)
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
