package io.openshm.patcher.ui.screens.selftest

import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantChecker
import io.openshm.patcher.adb.GrantState
import io.openshm.patcher.adb.PmParsers
import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.toAdbGrant
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.install.GrantRow
import io.openshm.patcher.ui.screens.install.LeftoverBridges
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/* What the self-test needs from the outside world. Each one is an interface so the checks run in JVM tests. */

/** An installed package as the platform reports it. [signerSha256] are lowercase hex digests of the signing certificates. */
data class InstalledPackage(
    val versionName: String?,
    val signerSha256: Set<String>,
    val apkPath: String?,
    val versionCode: Long? = null,
    val lastUpdateTime: Long? = null,
)

interface PackageInspector {
    /** Null when the package is not installed (or not visible to this app). */
    fun inspect(pkg: String): InstalledPackage?

    /** Packages with a notification listener or a launcher activity (the phone bridge and Samsung Health+); the phone cannot list all packages. */
    fun candidatePackages(): List<String> = emptyList()
}

interface FileHasher {
    /** Lowercase hex SHA-256 of the file, or null when it cannot be read. */
    fun sha256(path: String): String?
}

/** What the bridge's status provider answered. */
sealed interface BridgeStatus {
    data class Reading(val serviceRunning: Boolean, val linkUp: Boolean, val hello: Boolean) : BridgeStatus

    /** No provider answers on the authority: the bridge is not installed or not visible. */
    data object ProviderMissing : BridgeStatus

    /** The provider threw a [SecurityException]: this app's certificate is not the one the bridge trusts. */
    data object Denied : BridgeStatus
}

interface BridgeStatusSource {
    /** Blocking query of the status provider with [authority]. */
    fun query(authority: String): BridgeStatus
}

/** The phone's shell on the watch (ADB). */
interface WatchShell {
    /** True when the watch can be reached right now. */
    suspend fun connect(): Boolean

    /** Runs one command; [IOException] when the link breaks. */
    suspend fun run(command: String): ShellResult
}

/** Output parsers that only the watch checks need. */
object SelfTestParsers {
    private val sha256Line = Regex("""^([0-9a-fA-F]{64})(\s|$)""")

    /** The digest at the start of a `sha256sum <file>` line, lowercase; null when there is none. */
    fun sha256sum(output: String): String? =
        output.lineSequence().mapNotNull { sha256Line.find(it.trim())?.groupValues?.get(1)?.lowercase() }.firstOrNull()

    private val safePath = Regex("""/[A-Za-z0-9_./=~+@-]+""")

    /** True for an absolute path that is safe to put in a shell command without quoting. */
    fun isSafePath(path: String): Boolean = safePath.matches(path) && !path.contains("..")

    /** What identifies one installed version of a package on the watch: `versionCode` and `lastUpdateTime`. */
    data class WatchPackageStamp(val versionCode: Long, val lastUpdateTime: String)

    private val lastUpdate = Regex("""\blastUpdateTime=([^\r\n]+)""")

    /** The stamp in `dumpsys package <pkg>` output; null when either field is missing. */
    fun packageStamp(dumpsys: String): WatchPackageStamp? {
        val code = PmParsers.parseVersion(dumpsys)?.versionCode ?: return null
        val updated = lastUpdate.find(dumpsys)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return WatchPackageStamp(code, updated)
    }
}

/** Where the fingerprint of the last manual answers is kept, so the questions are not asked again while nothing changed. */
interface ManualAnswersStore {
    fun fingerprint(): String?
    fun save(fp: String)
    fun clear()
}

private data class Outcome(val status: CheckStatus, val detail: SelfTestText? = null, val fix: SelfTestFix? = null)

private fun text(res: Int, vararg args: String) = SelfTestText(res, args.toList())

private class Check(val section: Int, val row: SelfTestRow, val run: suspend (RunContext) -> Outcome)

/** One run's shared watch access: connected once, commands one at a time (ADB streams are not shared between rows). */
private class RunContext(private val watch: WatchShell) {
    private val connectLock = Mutex()
    private var connected: Boolean? = null
    private val shellLock = Mutex()

    suspend fun watchAvailable(): Boolean = connectLock.withLock { connected ?: watch.connect().also { connected = it } }

    suspend fun shell(command: String): ShellResult = shellLock.withLock { watch.run(command) }
}

/** Row id of the watch access check of output [outputId]. */
internal fun watchGrantsRowId(outputId: String) = "watch-grants-$outputId"

/** The output id of a [watchGrantsRowId] row, or null for other rows. */
internal fun outputOfWatchGrantsRow(rowId: String): String? = rowId.removePrefix("watch-grants-").takeIf { it != rowId }

/** The watch could not be reached at all, as opposed to a command that ran and went wrong. */
private fun IOException.isLinkFailure(): Boolean =
    this !is AdbException || this is AdbException.ConnectionFailed || this is AdbException.Discovery ||
        this is AdbException.PairingRequired || this is AdbException.AuthenticationRejected || this is AdbException.Timeout
private const val SECTION_PHONE = 0
private const val SECTION_WATCH = 1
private const val SECTION_LINK = 2
private const val SECTION_HUMAN = 3

/**
 * The self-test: one row per fact worth knowing about the installed apps, each checked against the
 * real device and run in parallel. Plain code over interfaces, so it runs in JVM tests;
 * [SelfTestViewModel] wires the real ones.
 *
 * Phone rows: the package is installed, signed with the user's key and byte-identical to the last
 * build; the access the catalogue lists is granted; the bridge reports a live link. Watch rows (only
 * when the watch can be reached; otherwise skipped with a way to pair): the same package and access
 * checks over ADB and the stock apps being off. What nothing can read is asked as a question.
 */
internal class SelfTestRunner(
    private val catalogue: LoadedCatalogue,
    private val outputIds: List<String>,
    private val prefix: String,
    private val build: () -> BuiltBuild?,
    private val signingCertSha256: () -> String?,
    private val packages: PackageInspector,
    private val hasher: FileHasher,
    private val phoneGrants: PhoneGrants,
    private val bridge: BridgeStatusSource,
    private val watch: WatchShell,
    private val io: CoroutineDispatcher,
    /** The phone's OS build fingerprint. */
    private val phoneBuild: () -> String,
    private val manualAnswers: ManualAnswersStore,
    /** Where a check that threw is reported (the app's log; nothing in JVM tests). */
    private val logFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val outputs = catalogue.outputs.filter { it.id in outputIds }

    private val checks: List<Check> by lazy {
        buildList {
            for (out in outputs.filter { it.device == Device.Phone }) {
                add(Check(SECTION_PHONE, pendingRow("phone-pkg-${out.id}", text(R.string.selftest_package_title, out.label), SelfTestFix.InstallPhone)) { phonePackage(out) })
                if (catalogue.grants[out.id].orEmpty().isNotEmpty()) {
                    add(Check(SECTION_PHONE, pendingRow("phone-grants-${out.id}", text(R.string.selftest_grants_title, out.label), SelfTestFix.InstallPhone)) { phoneAccess(out) })
                }
            }
            if (hasBridge(Device.Phone)) {
                add(Check(SECTION_PHONE, pendingRow("phone-leftovers", text(R.string.selftest_leftovers_title), SelfTestFix.InstallPhone)) { phoneLeftovers() })
            }
            for (out in outputs.filter { it.device == Device.Watch }) {
                add(Check(SECTION_WATCH, pendingRow("watch-pkg-${out.id}", text(R.string.selftest_package_title, out.label), SelfTestFix.InstallWatch)) { watchPackage(it, out) })
                if (catalogue.grants[out.id].orEmpty().isNotEmpty()) {
                    add(Check(SECTION_WATCH, pendingRow(watchGrantsRowId(out.id), text(R.string.selftest_grants_title, out.label), SelfTestFix.WatchGrants)) { watchAccess(it, out) })
                }
            }
            if (hasBridge(Device.Watch)) {
                add(Check(SECTION_WATCH, pendingRow("watch-leftovers", text(R.string.selftest_leftovers_title), SelfTestFix.InstallWatch)) { watchLeftovers(it) })
            }
            // A stock app is turned off for the output that replaces it: not checked once that output was restored.
            val stockApps = catalogue.stockAppsToDisable.filter { stock -> stock.device == Device.Watch && outputs.any { it.id == stock.replacedBy } }
            for (stock in stockApps) {
                add(Check(SECTION_WATCH, pendingRow("watch-stock-${stock.packageName}", text(R.string.selftest_stock_title, stock.label), SelfTestFix.DisableStock)) { watchStock(it, stock.packageName, stock.optional) })
            }
            outputs.firstNotNullOfOrNull { it.statusAuthority(prefix) }?.let { authority ->
                add(Check(SECTION_LINK, pendingRow("link-bridge", text(R.string.selftest_bridge_link_title), SelfTestFix.InstallPhone)) { bridgeLink(authority) })
            }
        }
    }

    private fun hasBridge(device: Device) = outputs.any { it.device == device && it.singleCopy }

    private fun pendingRow(id: String, title: SelfTestText, fix: SelfTestFix?) = SelfTestRow(id, title, CheckStatus.Pending, fix = fix)

    private val humanRows = listOf(
        SelfTestRow("human-platform", text(R.string.selftest_watch_platform), CheckStatus.Pending, fix = SelfTestFix.DevMode, question = R.string.selftest_platform_question),
        SelfTestRow("human-watch-connected", text(R.string.selftest_gms_title), CheckStatus.Pending, fix = SelfTestFix.WearablePairing, question = R.string.selftest_gms_question),
        SelfTestRow("human-shm", text(R.string.selftest_shm_title), CheckStatus.Pending, question = R.string.selftest_shm_question),
        SelfTestRow("human-ecg", text(R.string.selftest_ecg_title), CheckStatus.Pending, question = R.string.selftest_ecg_question),
    )

    /** Every row, waiting; sections without rows are left out. */
    fun initial(): SelfTestUiState {
        val titles = listOf(R.string.selftest_section_phone, R.string.selftest_section_watch, R.string.selftest_section_link, R.string.selftest_section_human)
        val rows = checks.groupBy({ it.section }, { it.row }) + (SECTION_HUMAN to humanRows)
        return SelfTestUiState(
            titles.indices.mapNotNull { i -> rows[i]?.takeIf { it.isNotEmpty() }?.let { SelfTestSection(titles[i], it) } },
            resolvingManual = true,
        )
    }

    /**
     * Runs the automated rows in parallel, writing each row's progress into [state]. Questions are left to
     * the user, unless nothing they depend on changed since the last time they were all answered yes.
     */
    suspend fun run(state: MutableStateFlow<SelfTestUiState>) {
        state.update { it.copy(resolvingManual = true, manualFingerprint = null, manualReused = false) }
        val context = RunContext(watch)
        coroutineScope {
            for (check in checks) {
                launch {
                    val id = check.row.id
                    state.update { it.withRow(id) { row -> row.copy(status = CheckStatus.Running, detail = null) } }
                    val outcome = execute(check, context)
                    state.update { it.withRow(id) { row -> row.copy(status = outcome.status, detail = outcome.detail, fix = outcome.fix ?: row.fix) } }
                }
            }
        }
        val failed = state.value.sections.flatMap { it.rows }.any { !it.isHuman && it.status == CheckStatus.Failed }
        val fingerprint = if (failed) null else manualFingerprint(state.value, context)
        val reused = fingerprint != null && fingerprint == stored()
        state.update { current ->
            val resolved = current.copy(resolvingManual = false, manualFingerprint = fingerprint, manualReused = reused)
            if (!reused) resolved else resolved.copy(sections = resolved.sections.map { s ->
                s.copy(rows = s.rows.map { if (it.isHuman) it.copy(status = CheckStatus.Passed, detail = SelfTestText(R.string.selftest_detail_answer_reused)) else it })
            })
        }
    }

    private fun stored(): String? = try {
        manualAnswers.fingerprint()
    } catch (e: RuntimeException) {
        logFailure("reading the stored manual answers failed", e)
        null
    }

    /**
     * SHA-256 of everything the manual answers depend on, or null when it cannot be known (a package
     * row did not pass, no build record, the watch cannot be read). Components, sorted: each checked
     * output's build sha256; phone: package, versionCode and lastUpdateTime of the companion app and its
     * watch plugins when installed; the phone's OS build fingerprint; watch: `ro.build.fingerprint` and
     * the Health Platform's versionCode and lastUpdateTime.
     */
    private suspend fun manualFingerprint(state: SelfTestUiState, context: RunContext): String? = try {
        computeFingerprint(state, context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logFailure("manual answers fingerprint failed", e)
        null
    } catch (e: RuntimeException) {
        logFailure("manual answers fingerprint failed", e)
        null
    }

    private suspend fun computeFingerprint(state: SelfTestUiState, context: RunContext): String? {
        val pkgRows = state.sections.flatMap { it.rows }.filter { it.id.startsWith("phone-pkg-") || it.id.startsWith("watch-pkg-") }
        if (pkgRows.any { it.status != CheckStatus.Passed }) return null
        val parts = ArrayList<String>()
        for (out in outputs) parts += "output:${out.id}:${recordOf(out.id)?.sha256?.lowercase() ?: return null}"
        val companion = catalogue.platform.companionApp
        for (pkg in listOf(companion.packageName) + companion.watchPlugins) {
            val installed = withContext(io) { packages.inspect(pkg) } ?: continue
            parts += "phone:$pkg:${installed.versionCode}:${installed.lastUpdateTime}"
        }
        parts += "phone-build:${withContext(io) { phoneBuild() }}"
        if (!context.watchAvailable()) return null
        val prop = context.shell("getprop ro.build.fingerprint")
        val watchBuild = prop.stdout.trim().takeIf { prop.isSuccess && it.isNotEmpty() } ?: return null
        parts += "watch-build:$watchBuild"
        val health = catalogue.platform.healthPlatform.packageName
        val dump = context.shell("dumpsys package $health")
        val stamp = (if (dump.isSuccess) SelfTestParsers.packageStamp(dump.stdout) else null) ?: return null
        parts += "watch:$health:${stamp.versionCode}:${stamp.lastUpdateTime}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.sorted().joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun execute(check: Check, context: RunContext): Outcome = try {
        check.run(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logFailure("check ${check.row.id} failed", e)
        if (check.section == SECTION_WATCH && e.isLinkFailure()) {
            Outcome(CheckStatus.Warning, text(R.string.selftest_detail_watch_no_answer), SelfTestFix.WatchPair)
        } else {
            Outcome(CheckStatus.Warning, text(R.string.selftest_detail_error))
        }
    } catch (e: RuntimeException) {
        logFailure("check ${check.row.id} failed", e)
        Outcome(CheckStatus.Warning, text(R.string.selftest_detail_error))
    }

    // Phone

    private suspend fun phonePackage(out: OutputApp): Outcome {
        val pkg = out.packageName(prefix)
        val installed = withContext(io) { packages.inspect(pkg) }
            ?: return Outcome(CheckStatus.Failed, text(R.string.selftest_detail_not_installed), SelfTestFix.InstallPhone)
        val cert = withContext(io) { signingCertSha256() }
            ?: return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_cert_unavailable))
        if (cert.lowercase() !in installed.signerSha256.map { it.lowercase() }) {
            return Outcome(CheckStatus.Failed, text(R.string.selftest_detail_wrong_cert), SelfTestFix.InstallPhone)
        }
        val record = recordOf(out.id)
            ?: return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_no_build), SelfTestFix.Build)
        val path = installed.apkPath ?: return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_hash_unreadable))
        val actual = withContext(io) { hasher.sha256(path) }
            ?: return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_hash_unreadable))
        if (!actual.equals(record.sha256, ignoreCase = true)) {
            return Outcome(CheckStatus.Failed, text(R.string.selftest_detail_hash_mismatch), SelfTestFix.InstallPhone)
        }
        val detail = installed.versionName?.let { text(R.string.selftest_detail_package_ok, it) } ?: text(R.string.selftest_detail_package_ok_no_version)
        return Outcome(CheckStatus.Passed, detail)
    }

    private suspend fun phoneAccess(out: OutputApp): Outcome {
        val pkg = out.packageName(prefix)
        if (withContext(io) { packages.inspect(pkg) } == null) {
            return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_skipped_not_installed), SelfTestFix.InstallPhone)
        }
        // Settings and the Doze list cannot be read by another app: those rows are left out.
        val rows = catalogue.grants[out.id].orEmpty()
            .filter { it.kind != GrantKind.SecureSetting && it.kind != GrantKind.GlobalSetting && it.kind != GrantKind.BatteryExemption }
            .map { GrantRow(it, CheckStatus.Pending) }
        val refreshed = withContext(io) { phoneGrants.refresh(pkg, rows) }
        val missing = refreshed.filter { it.status != CheckStatus.Passed && it.status != CheckStatus.Warning }.map { it.grant.name }
        val optional = refreshed.filter { it.status == CheckStatus.Warning }.map { it.grant.name }
        return accessOutcome(missing, optional, emptyList(), SelfTestFix.InstallPhone)
    }

    private suspend fun phoneLeftovers(): Outcome {
        val installed = withContext(io) { packages.candidatePackages() }
        return leftoversOutcome(LeftoverBridges.find(catalogue.outputs, Device.Phone, prefix, installed), SelfTestFix.InstallPhone)
    }

    private suspend fun bridgeLink(authority: String): Outcome {
        return when (val status = withContext(io) { bridge.query(authority) }) {
            BridgeStatus.ProviderMissing -> Outcome(CheckStatus.Failed, text(R.string.selftest_detail_bridge_missing), SelfTestFix.InstallPhone)
            BridgeStatus.Denied -> Outcome(CheckStatus.Failed, text(R.string.selftest_detail_bridge_denied), SelfTestFix.Build)
            is BridgeStatus.Reading -> when {
                !status.serviceRunning -> Outcome(CheckStatus.Failed, text(R.string.selftest_detail_bridge_not_running), SelfTestFix.InstallPhone)
                !status.linkUp -> Outcome(CheckStatus.Failed, text(R.string.selftest_detail_bridge_link_down))
                !status.hello -> Outcome(CheckStatus.Warning, text(R.string.selftest_detail_bridge_no_hello))
                else -> Outcome(CheckStatus.Passed, text(R.string.selftest_detail_bridge_ok))
            }
        }
    }

    // Watch

    private fun skippedWatch() = Outcome(CheckStatus.Warning, text(R.string.selftest_detail_watch_skipped), SelfTestFix.WatchPair)

    /** Base APK path of [pkg] on the watch, or null when it is not installed. */
    private suspend fun watchPath(context: RunContext, pkg: String): String? {
        val result = context.shell("pm path $pkg")
        return if (result.isSuccess) PmParsers.parsePaths(result.stdout).firstOrNull() else null
    }

    private suspend fun watchLeftovers(context: RunContext): Outcome {
        if (!context.watchAvailable()) return skippedWatch()
        val listed = context.shell("pm list packages")
        if (!listed.isSuccess) return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_error))
        val found = LeftoverBridges.find(catalogue.outputs, Device.Watch, prefix, PmParsers.parsePackageList(listed.stdout))
        return leftoversOutcome(found, SelfTestFix.InstallWatch)
    }

    private fun leftoversOutcome(found: List<String>, fix: SelfTestFix): Outcome =
        if (found.isEmpty()) Outcome(CheckStatus.Passed, text(R.string.selftest_detail_leftovers_none))
        else Outcome(CheckStatus.Failed, text(R.string.selftest_detail_leftovers_found, found.joinToString(", ")), fix)

    private suspend fun watchPackage(context: RunContext, out: OutputApp): Outcome {
        if (!context.watchAvailable()) return skippedWatch()
        val path = watchPath(context, out.packageName(prefix))
            ?: return Outcome(CheckStatus.Failed, text(R.string.selftest_detail_not_installed), SelfTestFix.InstallWatch)
        val record = recordOf(out.id) ?: return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_no_build), SelfTestFix.Build)
        if (!SelfTestParsers.isSafePath(path)) return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_hash_unreadable))
        val digest = context.shell("sha256sum $path").let { if (it.isSuccess) SelfTestParsers.sha256sum(it.stdout) else null }
            ?: return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_hash_unreadable))
        return if (digest.equals(record.sha256, ignoreCase = true)) {
            Outcome(CheckStatus.Passed, text(R.string.selftest_detail_watch_ok))
        } else {
            Outcome(CheckStatus.Failed, text(R.string.selftest_detail_hash_mismatch), SelfTestFix.InstallWatch)
        }
    }

    private suspend fun watchAccess(context: RunContext, out: OutputApp): Outcome {
        if (!context.watchAvailable()) return skippedWatch()
        val pkg = out.packageName(prefix)
        if (watchPath(context, pkg) == null) {
            return Outcome(CheckStatus.Warning, text(R.string.selftest_detail_skipped_not_installed), SelfTestFix.InstallWatch)
        }
        val grants: List<Grant> = catalogue.grants[out.id].orEmpty()
        val mapped = ArrayList<Pair<Grant, AdbGrant>>()
        val unknown = ArrayList<String>()
        for (grant in grants) {
            try {
                mapped += grant to grant.toAdbGrant()
            } catch (e: IllegalArgumentException) {
                unknown += grant.name
            }
        }
        val checks = GrantChecker { command -> context.shell(command) }.check(pkg, mapped.map { it.second })
        val missing = ArrayList<String>()
        val optional = ArrayList<String>()
        checks.forEachIndexed { i, check ->
            val (grant, _) = mapped[i]
            when (check.state) {
                GrantState.Granted -> Unit
                GrantState.Missing -> if (grant.optional) optional += grant.name else missing += grant.name
                GrantState.Unknown -> unknown += grant.name
            }
        }
        return accessOutcome(missing, optional, unknown, SelfTestFix.WatchGrants)
    }

    private suspend fun watchStock(context: RunContext, pkg: String, optional: Boolean): Outcome {
        if (!context.watchAvailable()) return skippedWatch()
        val disabled = context.shell("pm list packages -d")
        if (disabled.isSuccess && pkg in PmParsers.parsePackageList(disabled.stdout)) {
            return Outcome(CheckStatus.Passed, text(R.string.selftest_detail_stock_off))
        }
        if (watchPath(context, pkg) == null) return Outcome(CheckStatus.Passed, text(R.string.selftest_detail_stock_absent))
        return Outcome(
            if (optional) CheckStatus.Warning else CheckStatus.Failed,
            text(R.string.selftest_detail_stock_still_on),
            SelfTestFix.DisableStock,
        )
    }

    // Shared

    private fun recordOf(outputId: String): BuiltRecord? =
        build()?.takeIf { it.prefix == prefix }?.outputs?.firstOrNull { it.outputId == outputId }

    private fun accessOutcome(missing: List<String>, optional: List<String>, unknown: List<String>, fix: SelfTestFix): Outcome = when {
        missing.isNotEmpty() -> Outcome(CheckStatus.Failed, text(R.string.selftest_detail_grants_missing, missing.joinToString(", ")), fix)
        unknown.isNotEmpty() -> Outcome(CheckStatus.Warning, text(R.string.selftest_detail_grants_unknown, unknown.joinToString(", ")), fix)
        optional.isNotEmpty() -> Outcome(CheckStatus.Warning, text(R.string.selftest_detail_grants_optional, optional.joinToString(", ")), fix)
        else -> Outcome(CheckStatus.Passed, text(R.string.selftest_detail_grants_ok))
    }
}

internal fun SelfTestUiState.withRow(id: String, change: (SelfTestRow) -> SelfTestRow) =
    copy(sections = sections.map { s -> s.copy(rows = s.rows.map { if (it.id == id) change(it) else it }) })

/** Records the answer to the first open question: "yes" passes it, "no" fails it (and offers its fix). */
internal fun SelfTestUiState.answer(yes: Boolean): SelfTestUiState {
    val open = sections.flatMap { it.rows }.firstOrNull { it.isHuman && it.status == CheckStatus.Pending } ?: return this
    return withRow(open.id) {
        if (yes) it.copy(status = CheckStatus.Passed, detail = null)
        else it.copy(status = CheckStatus.Failed, detail = SelfTestText(R.string.selftest_detail_answered_no))
    }
}

/** Back to waiting for an answer, every question again (the stored answers stay until the user answers). */
internal fun SelfTestUiState.askAgain(): SelfTestUiState = copy(
    sections = sections.map { s -> s.copy(rows = s.rows.map { if (it.isHuman) it.copy(status = CheckStatus.Pending, detail = null) else it }) },
    manualReused = false,
)
