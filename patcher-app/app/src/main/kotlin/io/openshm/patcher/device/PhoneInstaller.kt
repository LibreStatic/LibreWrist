package io.openshm.patcher.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import java.security.MessageDigest
import android.os.Build
import io.openshm.patcher.adb.InstallResult
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the phone install screen needs from the platform installer; tests use fakes. */
interface PhoneInstaller {
    /** False until the user allows this app to install packages (Settings, "Install unknown apps"). */
    fun canInstallPackages(): Boolean

    /**
     * True when [packageName] is already installed with the version and signing certificate(s) of
     * [apk], so sending it again would change nothing.
     */
    fun isUpToDate(apk: File, packageName: String): Boolean

    /**
     * Installs [apk] as [packageName] (an update when it is already installed with the same
     * signature). Never removes anything. [IOException] when the file cannot be sent.
     */
    suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult

    /** Whether [packageName] is installed, and if so whether it is a system app. */
    fun packageKind(packageName: String): PackageKind

    /**
     * Removes [packageName] together with its data. Only called after the user confirmed it in
     * this app; the system then asks once more (through [SessionPhoneInstaller]'s `onUserAction`).
     */
    suspend fun uninstall(packageName: String): UninstallResult
}

/**
 * Sends the APK through a `PackageInstaller` session. The result arrives at [PhoneInstallReceiver];
 * when Android needs the user's confirmation, [onUserAction] gets the intent to start (the screen
 * does it, as the confirmation must come from the foreground activity).
 */
class SessionPhoneInstaller(
    context: Context,
    private val onUserAction: (Intent) -> Unit,
) : PhoneInstaller {
    private val context = context.applicationContext

    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    override fun isUpToDate(apk: File, packageName: String): Boolean = try {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val built = pm.getPackageArchiveInfo(apk.path, flags)
        val installed = pm.getPackageInfo(packageName, flags)
        val builtSigners = built?.let(::signersOf)
        built != null && built.packageName == packageName &&
            built.longVersionCode == installed.longVersionCode &&
            !builtSigners.isNullOrEmpty() && builtSigners == signersOf(installed)
    } catch (e: PackageManager.NameNotFoundException) {
        false // not installed, or not visible to this app: install it
    } catch (e: RuntimeException) {
        false
    }

    private fun signersOf(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun packageKind(packageName: String): PackageKind = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) PackageKind.System else PackageKind.User
    } catch (e: PackageManager.NameNotFoundException) {
        PackageKind.Missing
    }

    override suspend fun uninstall(packageName: String): UninstallResult = withContext(Dispatchers.IO) {
        val key = PhoneInstallReceiver.nextUninstallKey()
        val result = CompletableDeferred<UninstallResult>()
        PhoneInstallReceiver.listenUninstall(key) { intent -> onUninstallBroadcast(intent, result) }
        try {
            val broadcast = PendingIntent.getBroadcast(
                context,
                key,
                Intent(context, PhoneInstallReceiver::class.java)
                    .setAction(PhoneInstallReceiver.ACTION_UNINSTALL)
                    .putExtra(PhoneInstallReceiver.EXTRA_UNINSTALL_KEY, key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            context.packageManager.packageInstaller.uninstall(packageName, broadcast.intentSender)
            result.await()
        } catch (e: SecurityException) {
            UninstallResult.Failure(e.message.orEmpty())
        } finally {
            PhoneInstallReceiver.forgetUninstall(key)
        }
    }

    private fun onUninstallBroadcast(intent: Intent, result: CompletableDeferred<UninstallResult>) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) onUserAction(confirm) else result.complete(UninstallResult.Failure("No confirmation screen was provided"))
            }
            PackageInstaller.STATUS_SUCCESS -> result.complete(UninstallResult.Success)
            else -> result.complete(
                UninstallResult.Failure(
                    listOfNotNull(
                        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "STATUS_FAILURE_ABORTED" else null,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() },
                    ).joinToString(": ").ifBlank { "STATUS_FAILURE" },
                ),
            )
        }
    }

    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult =
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(apk.length())
                // Only honoured for updates of apps this app installed; otherwise Android asks anyway.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            val result = CompletableDeferred<InstallResult>()
            var committed = false
            try {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            var written = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                written += n
                                onProgress(if (apk.length() > 0) written.toFloat() / apk.length() else 0f)
                            }
                        }
                        session.fsync(out)
                    }
                    PhoneInstallReceiver.listen(id) { intent -> onBroadcast(intent, result) }
                    val broadcast = PendingIntent.getBroadcast(
                        context,
                        id,
                        Intent(context, PhoneInstallReceiver::class.java).setAction(PhoneInstallReceiver.ACTION),
                        // Mutable: the system adds the status extras to this intent.
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(broadcast.intentSender)
                    committed = true
                }
                result.await()
            } catch (e: IOException) {
                throw e
            } catch (e: SecurityException) {
                throw IOException("The installer refused the session: ${e.message}", e)
            } finally {
                PhoneInstallReceiver.forget(id)
                if (!result.isCompleted || !committed) runCatching { installer.abandonSession(id) }
            }
        }

    private fun onBroadcast(intent: Intent, result: CompletableDeferred<InstallResult>) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            if (confirm != null) onUserAction(confirm) else result.complete(failure("STATUS_PENDING_USER_ACTION", "No confirmation screen was provided"))
            return
        }
        result.complete(
            installResultFor(
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                intent.getIntExtra(EXTRA_LEGACY_STATUS, 0),
            ) ?: failure("STATUS_UNKNOWN", "Unexpected installer status $status"),
        )
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"
    }
}

private fun failure(code: String, message: String) = InstallResult.Failure(code, message, InstallResult.Stage.COMMIT)

private val PM_CODE = Regex("INSTALL_[A-Z_]+")

/**
 * Turns the status extras of the installer's callback into a result; null for
 * `STATUS_PENDING_USER_ACTION`, which is not final. The package manager's own code comes from the
 * message (it starts with it) or from the legacy status, so a signature clash can be told apart
 * from any other refusal.
 */
internal fun installResultFor(status: Int, message: String?, legacyStatus: Int): InstallResult? {
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return null
    if (status == PackageInstaller.STATUS_SUCCESS) return InstallResult.Success(0)
    val code = message?.let { PM_CODE.find(it)?.value }
        ?: LEGACY_CODES[legacyStatus]
        ?: when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE"
            PackageInstaller.STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "STATUS_FAILURE_TIMEOUT"
            else -> "STATUS_FAILURE"
        }
    return failure(code, message.orEmpty())
}

/** The package manager's legacy codes worth naming (`PackageManager.INSTALL_FAILED_*` are hidden constants). */
private val LEGACY_CODES = mapOf(
    -7 to "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
    -25 to "INSTALL_FAILED_VERSION_DOWNGRADE",
    -12 to "INSTALL_FAILED_OLDER_SDK",
    -4 to "INSTALL_FAILED_INSUFFICIENT_STORAGE",
)

/** Receives the installer's status callbacks and hands them to the install waiting on that session. */
class PhoneInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_UNINSTALL) {
            uninstallListeners[intent.getIntExtra(EXTRA_UNINSTALL_KEY, -1)]?.invoke(intent)
            return
        }
        if (intent.action != ACTION) return
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        // No listener means the process was restarted during the install; the screen starts again.
        listeners[id]?.invoke(intent)
    }

    companion object {
        const val ACTION = "io.openshm.patcher.action.PHONE_INSTALL_STATUS"
        const val ACTION_UNINSTALL = "io.openshm.patcher.action.PHONE_UNINSTALL_STATUS"
        const val EXTRA_UNINSTALL_KEY = "io.openshm.patcher.extra.UNINSTALL_KEY"
        private val listeners = ConcurrentHashMap<Int, (Intent) -> Unit>()
        private val uninstallListeners = ConcurrentHashMap<Int, (Intent) -> Unit>()
        private val uninstallKeys = AtomicInteger()

        internal fun nextUninstallKey(): Int = uninstallKeys.incrementAndGet()

        internal fun listenUninstall(key: Int, onStatus: (Intent) -> Unit) {
            uninstallListeners[key] = onStatus
        }

        internal fun forgetUninstall(key: Int) {
            uninstallListeners.remove(key)
        }

        internal fun listen(sessionId: Int, onStatus: (Intent) -> Unit) {
            listeners[sessionId] = onStatus
        }

        internal fun forget(sessionId: Int) {
            listeners.remove(sessionId)
        }
    }
}
