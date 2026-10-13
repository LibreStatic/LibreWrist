package io.openshm.patcher.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.openshm.patcher.MainActivity
import io.openshm.patcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the device-log capture ([DeviceLogs]) running while the app is in the background, so the user can
 * reproduce a problem on the watch or phone and read the log afterwards. The ongoing notification shows
 * the line counts, with Stop (ends the capture) and Open (opens the app on the logs screen through
 * [EXTRA_OPEN_ROUTE]). It is not sticky: if the process dies the capture is gone, the buffers stay.
 */
class DeviceLogService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DeviceLogs.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification(DeviceLogs.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (started) return START_NOT_STICKY
        started = true
        AdbPresence.start(this, PRESENCE_OWNER)
        DeviceLogs.beginCapture(this)
        scope.launch {
            DeviceLogs.state.sample(NOTIFICATION_INTERVAL_MS).collect { state ->
                if (!state.capturing) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        AdbPresence.stop(PRESENCE_OWNER)
        DeviceLogs.endCapture()
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.device_logs_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(state: DeviceLogsState): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DeviceLogService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_ROUTE, OPEN_ROUTE_DEVICE_LOGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.device_logs_notification_title))
            .setContentText(getString(R.string.device_logs_notification_text, state.watch.lineCount, state.phone.lineCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.device_logs_action_stop), stop).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.device_logs_action_open), open).build())
            .build()
    }

    companion object {
        /** Extra on the intent that opens [MainActivity] from the notification; the value names the route to show. */
        const val EXTRA_OPEN_ROUTE = "io.openshm.patcher.OPEN_ROUTE"

        /** Value of [EXTRA_OPEN_ROUTE] for the device logs screen. */
        const val OPEN_ROUTE_DEVICE_LOGS = "device-logs"

        private const val ACTION_STOP = "io.openshm.patcher.device.STOP_LOGS"
        private const val CHANNEL_ID = "device-logs"
        private const val NOTIFICATION_ID = 3
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val PRESENCE_OWNER = "device-logs"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DeviceLogService::class.java))
        }
    }
}
