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
import android.util.Log
import io.openshm.patcher.MainActivity
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbKeyStore
import io.openshm.patcher.adb.LibAdbBackend
import io.openshm.patcher.adb.SelfAdbPairingNotifier
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.adb.SelfAdbTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the phone's Wireless debugging fallback alive while the user is in Developer options: a
 * backgrounded process is frozen within seconds on recent Android, which would stall discovery and
 * pairing. It owns the [SelfAdbPairingNotifier] (and so the session and the connection) from the
 * start until [SelfAdbController] says the attempt is over, fails, or [TIMEOUT_MS] pass. It is not
 * sticky: if the process dies the attempt is gone.
 */
class SelfAdbService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var notifier: SelfAdbPairingNotifier? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(LOG_TAG, "service start, attempt phase=${SelfAdbController.state.value.phase}")
        createChannel()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (job?.isActive == true) return START_NOT_STICKY
        if (!SelfAdbController.state.value.active) {
            Log.i(LOG_TAG, "service stops: no attempt is active")
            stopSelf()
            return START_NOT_STICKY
        }
        val fresh = SelfAdbPairingNotifier(this, LibAdbBackend(AdbKeyStore.forContext(this)), scope, texts())
        try {
            fresh.start()
        } catch (e: AdbException.Notifications) {
            Log.i(LOG_TAG, "service stops: notifications not allowed")
            SelfAdbController.fail(e.message, needsNotifications = true)
            stopSelf()
            return START_NOT_STICKY
        }
        notifier = fresh
        SelfAdbController.setCodeSink { fresh.session.submitCode(it) }
        job = scope.launch {
            val follow = launch {
                fresh.session.state.collect {
                    if (it is SelfAdbState.Connected) SelfAdbTrust.record(this@SelfAdbService)
                    SelfAdbController.onSession(it)
                    Log.i(LOG_TAG, "attempt phase=${SelfAdbController.state.value.phase}")
                }
            }
            val timeout = launch {
                delay(TIMEOUT_MS)
                Log.i(LOG_TAG, "attempt timed out")
                SelfAdbController.fail(getString(R.string.phone_adb_timeout))
            }
            SelfAdbController.state.first { !it.active }
            follow.cancel()
            timeout.cancel()
            Log.i(LOG_TAG, "service ends: attempt over")
            end()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "service destroyed, attempt active=${SelfAdbController.state.value.active}")
        if (SelfAdbController.state.value.active) SelfAdbController.fail(getString(R.string.phone_adb_interrupted))
        scope.cancel()
        releaseNotifier()
        super.onDestroy()
    }

    private fun end() {
        releaseNotifier()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseNotifier() {
        SelfAdbController.setCodeSink(null)
        notifier?.stop() // also closes the connection
        notifier = null
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.selfadb_service_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.selfadb_service_title))
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun texts() = SelfAdbTexts(
        channelName = getString(R.string.selfadb_channel),
        codeChannelName = getString(R.string.selfadb_code_channel),
        waitingForService = getString(R.string.selfadb_waiting_service),
        enterCodeTitle = getString(R.string.selfadb_enter_code_title),
        enterCodeBody = getString(R.string.selfadb_enter_code_body),
        replyLabel = getString(R.string.selfadb_reply_label),
        replyAction = getString(R.string.selfadb_reply_action),
        wrongCode = getString(R.string.selfadb_wrong_code),
        pairing = getString(R.string.selfadb_pairing),
        connecting = getString(R.string.selfadb_connecting),
        connected = getString(R.string.selfadb_connected),
        ambiguous = getString(R.string.selfadb_ambiguous),
        failed = getString(R.string.selfadb_failed),
    )

    companion object {
        private const val LOG_TAG = "SelfAdb"
        private const val CHANNEL_ID = "selfadb_service"
        private const val NOTIFICATION_ID = 2
        const val TIMEOUT_MS = 10L * 60 * 1000

        /** Removes notifications left by an earlier process; call it when the process starts. */
        fun clearStale(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            SelfAdbPairingNotifier.clearStale(context)
        }

        /** Starts the service for an attempt that [SelfAdbController.begin] accepted. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SelfAdbService::class.java))
        }
    }
}
