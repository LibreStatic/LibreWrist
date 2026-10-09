package io.openshm.patcher.adb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * User-facing notification strings. These are the only user-facing text in this module; the app
 * passes localised versions. Defaults are English.
 */
data class SelfAdbTexts(
    val channelName: String = "Pairing with this phone",
    val waitingForService: String = "Open Developer options, Wireless debugging, Pair device with pairing code",
    val enterCodeTitle: String = "Enter the pairing code",
    val enterCodeBody: String = "Type the six-digit code shown on the pairing screen.",
    val replyLabel: String = "Pairing code",
    val replyAction: String = "Enter code",
    val wrongCode: String = "That code did not work. Check the pairing screen and try again.",
    val pairing: String = "Pairing...",
    val connecting: String = "Connecting...",
    val connected: String = "Connected to this phone's ADB",
    val failed: String = "Could not connect",
)

/**
 * Self-ADB (Shizuku-style): lets the user pair the phone with its own Wireless debugging by typing
 * the code into a notification, because the system pairing dialog disappears when the app's
 * activity is shown. Hooks: [session] for state, [SelfAdbPairingReceiver] for the typed code.
 *
 * Needs the `POST_NOTIFICATIONS` runtime permission on Android 13+ (the app requests it). The
 * session is only as long-lived as the process: run it from a foreground service while pairing.
 */
class SelfAdbPairingNotifier(
    context: Context,
    backend: AdbBackend,
    scope: CoroutineScope,
    private val texts: SelfAdbTexts = SelfAdbTexts(),
    private val smallIconRes: Int = android.R.drawable.ic_dialog_info,
    discovery: AdbDiscovery = AdbDiscovery(context),
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val scope = scope
    val session = SelfAdbSession(
        backend = backend,
        pairingServices = discovery.discover(AdbServiceType.PAIRING),
        connectServices = discovery.discover(AdbServiceType.CONNECT),
        scope = scope,
    )
    private var renderJob: Job? = null

    /** Posts the notification and starts discovery. Fails if notifications are not allowed. */
    fun start() {
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw AdbException.Notifications("The notification permission is not granted")
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, texts.channelName, NotificationManager.IMPORTANCE_HIGH),
        )
        current = this
        session.start()
        renderJob?.cancel()
        renderJob = scope.launch { session.state.collect { render(it) } }
    }

    fun stop() {
        renderJob?.cancel()
        session.stop()
        manager.cancel(NOTIFICATION_ID)
        if (current === this) current = null
    }

    internal fun onCodeSubmitted(code: String) {
        session.submitCode(code)
        // Clears the reply spinner immediately; the state collector then posts the real status.
        render(SelfAdbState.Pairing)
    }

    private fun render(state: SelfAdbState) {
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setOnlyAlertOnce(true)
        when (state) {
            SelfAdbState.Idle -> {
                manager.cancel(NOTIFICATION_ID)
                return
            }
            SelfAdbState.WaitingForPairingService ->
                builder.setContentTitle(texts.enterCodeTitle).setContentText(texts.waitingForService).setOngoing(true)
            is SelfAdbState.WaitingForCode -> {
                builder.setContentTitle(texts.enterCodeTitle)
                    .setContentText(if (state.lastFailure == null) texts.enterCodeBody else texts.wrongCode)
                    .setOngoing(true)
                    .addAction(replyAction())
            }
            SelfAdbState.Pairing -> builder.setContentTitle(texts.pairing).setOngoing(true)
            SelfAdbState.WaitingForConnectService, SelfAdbState.Connecting ->
                builder.setContentTitle(texts.connecting).setOngoing(true)
            is SelfAdbState.Connected -> builder.setContentTitle(texts.connected).setTimeoutAfter(10_000)
            is SelfAdbState.Failed -> builder.setContentTitle(texts.failed).setContentText(state.error.message)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun replyAction(): Notification.Action {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(texts.replyLabel).build()
        // Explicit component and FLAG_MUTABLE: required for inline reply on Android 12+.
        val intent = Intent(appContext, SelfAdbPairingReceiver::class.java).setAction(ACTION_PAIR)
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(appContext, smallIconRes), texts.replyAction, pending)
            .addRemoteInput(input)
            .build()
    }

    companion object {
        const val ACTION_PAIR = "io.openshm.patcher.adb.action.SELF_ADB_PAIR"
        const val KEY_CODE = "io.openshm.patcher.adb.extra.PAIRING_CODE"
        const val CHANNEL_ID = "openshm_self_adb"
        private const val NOTIFICATION_ID = 0x5e1f

        /** The active notifier, so the manifest-registered receiver can reach it. */
        @Volatile
        internal var current: SelfAdbPairingNotifier? = null
    }
}

/** Receives the code typed into the notification and hands it to the active session. */
class SelfAdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SelfAdbPairingNotifier.ACTION_PAIR) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(SelfAdbPairingNotifier.KEY_CODE)?.toString()?.trim()
            ?: return
        // No active notifier means the process was restarted: the user starts pairing again.
        SelfAdbPairingNotifier.current?.onCodeSubmitted(code)
    }
}
