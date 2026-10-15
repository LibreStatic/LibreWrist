package io.openshm.patcher.ui.screens.selftest

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.data.signingKeyStore
import io.openshm.patcher.device.WatchLink
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/* The real implementations behind the self-test's interfaces. Thin: the decisions are in SelfTestRunner. */

private fun hex(digest: ByteArray) = digest.joinToString("") { "%02x".format(it) }

class AndroidPackageInspector(context: Context) : PackageInspector {
    private val pm: PackageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun inspect(pkg: String): InstalledPackage? = try {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        InstalledPackage(info.versionName, signers, info.applicationInfo?.sourceDir, info.longVersionCode, info.lastUpdateTime)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    override fun listenerPackages(): List<String> = try {
        pm.queryIntentServices(Intent("android.service.notification.NotificationListenerService"), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    } catch (e: RuntimeException) {
        emptyList()
    }
}

class FileSha256 : FileHasher {
    override fun sha256(path: String): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        hex(digest.digest())
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** Reads the bridge's status provider (`StatusProvider`): one row, allowed for this app's certificate. */
class ContentResolverBridgeStatus(context: Context) : BridgeStatusSource {
    private val resolver = context.applicationContext.contentResolver

    override fun query(authority: String): BridgeStatus = try {
        val cursor = resolver.query(Uri.parse("content://$authority/status"), null, null, null, null)
        if (cursor == null) {
            BridgeStatus.ProviderMissing
        } else {
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    BridgeStatus.ProviderMissing
                } else {
                    fun flag(name: String) = c.getColumnIndex(name).let { it >= 0 && c.getInt(it) != 0 }
                    BridgeStatus.Reading(flag("service_running"), flag("link_up"), flag("hello"))
                }
            }
        }
    } catch (e: SecurityException) {
        BridgeStatus.Denied
    } catch (e: IllegalArgumentException) {
        BridgeStatus.ProviderMissing // "Unknown URL": no such provider
    }
}

/** The watch over the shared [WatchLink]. Not paired, or not reachable, is simply "not connected". */
class LinkWatchShell(private val link: WatchLink) : WatchShell {
    override suspend fun connect(): Boolean {
        if (link.rememberedHost == null) return false
        return try {
            link.client()
            true
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun run(command: String): ShellResult = link.client().shell(command)
}

/** The certificate digest of the user's signing key, or null when there is no key or it cannot be read. */
fun signingCertificateOrNull(context: Context): String? = try {
    signingKeyStore(context).takeIf { it.exists() }?.certificateSha256()
} catch (e: Exception) {
    null // keystore or file trouble: the row reports that the key could not be read
}
