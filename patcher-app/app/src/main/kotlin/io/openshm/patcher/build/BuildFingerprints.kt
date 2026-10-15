package io.openshm.patcher.build

import android.content.Context
import android.content.pm.PackageManager
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.data.signingKeyStore
import java.security.MessageDigest

/** The fingerprint (see [outputFingerprint]) each of [outputIds] would have if built now, by output id. */
fun currentFingerprints(
    context: Context,
    catalogue: LoadedCatalogue,
    prefix: String,
    outputIds: Collection<String>,
    disabled: Collection<String>,
): Map<String, String> {
    val signingCert = signingKeyStore(context).certificateSha256()
    val ownCert = ownCertificateSha256(context)
    return outputIds.associateWith { id ->
        outputFingerprint(catalogue, id, prefix, disabled, signingCert, BuildConfig.ENGINE_FINGERPRINT, ownCert, context.assets::open)
    }
}

/**
 * SHA-256 of the certificate this app is signed with (lowercase hex). With several signers the
 * platform's knownSigner check needs all of them, so that case is refused instead of pinning one.
 */
@Suppress("DEPRECATION")
fun ownCertificateSha256(context: Context): String? {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signingInfo = info.signingInfo ?: return null
    if (signingInfo.hasMultipleSigners()) return null
    val signature = signingInfo.apkContentsSigners?.firstOrNull() ?: return null
    return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}
