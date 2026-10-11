package io.openshm.patcher.build

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import io.openshm.patcher.MainActivity
import io.openshm.patcher.R
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.InputStore
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.signingKeyStore
import io.openshm.patcher.device.WatchKeepAlive
import io.openshm.patcher.ui.screens.build.BuildStage
import io.openshm.patcher.ui.screens.build.titleRes
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the build in a foreground service (type `specialUse`, see the manifest) so it survives the
 * screen closing or the app going to the background. [BuildController] is the source of truth; this
 * service only does the work, shows the progress notification and stops itself at the end.
 *
 * Lifecycle: a start while a build runs is a no-op. The service is not sticky: if the process dies the
 * build is gone (state Idle, no record), and the Build screen starts it again next time. A [WatchKeepAlive]
 * keeps the watch link alive during the build; after a successful build it outlives this service (the
 * process-wide instance stops when the watch install is done or after [WatchKeepAlive.AFTER_BUILD_MS]). Outputs are
 * built one after the other on one background coroutine; a partial wake lock (with a timeout) keeps the
 * CPU awake meanwhile.
 */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // From the notification's Cancel action: stop cooperatively; a stale tap with nothing running just stops the service.
            if (!BuildController.requestCancel() && job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, progressNotification(BuildController.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (job?.isActive == true || BuildController.state.value.phase == BuildRunPhase.Running) return START_NOT_STICKY
        val session = SetupSession.state.value
        val outputIds = session.watchProfile.outputIds
        val disabled = AppCatalogue.current.effectiveDisabled(session.disabledPatches)
        val key = buildKey(AppCatalogue.current.serial, session.packagePrefix, outputIds, disabled)
        if (!BuildController.tryBegin(key)) return START_NOT_STICKY
        val keepAlive = WatchKeepAlive.get(this)
        keepAlive.start()
        job = scope.launch {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openshm:build")
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
            val notifier = launch { BuildController.state.collect { notify(progressNotification(it)) } }
            try {
                execute(key, session.packagePrefix, outputIds, disabled)
            } catch (e: Throwable) {
                // The message of an engine or platform error; key material is never part of one.
                BuildController.onLog("FAILED: ${e.javaClass.simpleName}: ${e.message}")
                BuildController.fail("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                notifier.cancel()
                if (lock.isHeld) lock.release()
                // A built result is installed on the watch next, so the link stays alive a while longer
                // (until the watch install finishes, or this limit); otherwise nothing needs it.
                if (BuildController.state.value.phase == BuildRunPhase.Succeeded) keepAlive.stopAfter(WatchKeepAlive.AFTER_BUILD_MS)
                else keepAlive.stop()
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        if (BuildController.state.value.phase == BuildRunPhase.Running) {
            WatchKeepAlive.get(this).stop()
            BuildController.fail("The build was interrupted.")
        }
        super.onDestroy()
    }

    private fun execute(key: String, prefix: String, outputIds: List<String>, disabled: Set<String>) {
        val catalogue = AppCatalogue.current
        val inputs = InputStore(this)
        for (id in outputIds.mapNotNull { catalogue.output(it).inputId }.distinct()) {
            if (inputs.verifiedSize(catalogue.input(id)) == null) {
                BuildController.onLog("FAILED: input '$id' is missing or does not match; import it again")
                BuildController.fail("Input '$id' is missing or does not match the expected file.")
                return
            }
        }
        BuildController.onLog("preparing the signing key")
        val signingKey = signingKeyStore(this).load()
        val storage = BuildStorage(this)
        storage.record.clear()
        val runner = BuildRunner(
            catalogue = catalogue.engine,
            engine = RealBuildEngine(catalogue.engine),
            inputFile = inputs::file,
            openAsset = assets::open,
            outputsDir = storage.outputsDir,
            workDir = storage.workDir,
            cancelled = BuildController::isCancelRequested,
        )
        val sink = object : BuildSink {
            override fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage) =
                BuildController.onStage(outputId, index, count, stage)

            override fun onLog(line: String) = BuildController.onLog(line)
        }
        val outcome = runner.run(BuildRequest(outputIds, prefix, signingKey, ownCertificateSha256(), disabled), sink)
        if (outcome.cancelled) {
            BuildController.onLog("build cancelled; partial outputs deleted")
            BuildController.cancelled()
        } else if (outcome.ok) {
            storage.record.write(
                BuiltBuild(
                    key, prefix, catalogue.serial, System.currentTimeMillis(),
                    outcome.files.map { BuiltRecord(it.outputId, it.file.path, it.sizeBytes, it.sha256) },
                    buildFacts(catalogue, outputIds),
                ),
            )
            BuildController.succeed()
        } else {
            BuildController.fail(outcome.error ?: "The build failed.")
        }
    }

    /**
     * SHA-256 of the certificate this app is signed with (lowercase hex). With several signers the
     * platform's knownSigner check needs all of them, so that case is refused instead of pinning one.
     */
    @Suppress("DEPRECATION")
    private fun ownCertificateSha256(): String? {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = info.signingInfo ?: return null
        if (signingInfo.hasMultipleSigners()) return null
        val signature = signingInfo.apkContentsSigners?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun finish() {
        val state = BuildController.state.value
        if (state.phase == BuildRunPhase.Cancelled) {
            // The user knows: no "done" notification, just take the progress one away.
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            notify(finalNotification(state.phase == BuildRunPhase.Succeeded))
        }
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.build_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.build_channel_description) },
        )
    }

    private fun notify(notification: Notification) {
        // Without the notification permission this is silently dropped; the build does not depend on it.
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun builder(): Notification.Builder = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.build_notification_title))
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )

    private fun progressNotification(state: BuildRunState): Notification {
        val b = builder().setOngoing(true).setOnlyAlertOnce(true).addAction(cancelAction())
        val stage = state.stage
        val outputId = state.outputId
        if (stage != null && outputId != null) {
            b.setContentText(getString(R.string.build_notification_progress, AppCatalogue.current.output(outputId).label, getString(stage.titleRes())))
            b.setProgress(PROGRESS_MAX, (state.progress * PROGRESS_MAX).toInt(), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    /** An explicit, immutable intent to this (non-exported) service; no receiver is involved. */
    private fun cancelAction(): Notification.Action {
        val intent = Intent(this, BuildService::class.java).setAction(ACTION_CANCEL)
        val pending = PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.action_cancel), pending,
        ).build()
    }

    private fun finalNotification(ok: Boolean): Notification = builder()
        .setOngoing(false)
        .setAutoCancel(true)
        .setContentText(getString(if (ok) R.string.build_notification_done else R.string.build_notification_failed))
        .build()

    companion object {
        private const val ACTION_CANCEL = "io.openshm.patcher.action.CANCEL_BUILD"
        private const val CHANNEL_ID = "build"
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_MAX = 1000
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60 * 1000

        /** Starts the service; does nothing if a build is already running. */
        fun start(context: Context) {
            if (BuildController.state.value.phase == BuildRunPhase.Running) return
            context.startForegroundService(Intent(context, BuildService::class.java))
        }
    }
}
