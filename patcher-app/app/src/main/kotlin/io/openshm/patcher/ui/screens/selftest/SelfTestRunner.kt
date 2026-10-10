package io.openshm.patcher.ui.screens.selftest

import io.openshm.patcher.R
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
data class InstalledPackage(val versionName: String?, val signerSha256: Set<String>, val apkPath: String?)

interface PackageInspector {
    /** Null when the package is not installed (or not visible to this app). */
    fun inspect(pkg: String): InstalledPackage?
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

private const val SECTION_PHONE = 0
private const val SECTION_WATCH = 1
private const val SECTION_LINK = 2
private const val SECTION_HUMAN = 3

/** The status provider's authority is the bridge package prefix plus this suffix (`StatusProvider.AUTHORITY`). */
const val BRIDGE_STATUS_AUTHORITY_SUFFIX = ".shmbridge.phone.status"

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
            for (out in outputs.filter { it.device == Device.Watch }) {
                add(Check(SECTION_WATCH, pendingRow("watch-pkg-${out.id}", text(R.string.selftest_package_title, out.label), SelfTestFix.InstallWatch)) { watchPackage(it, out) })
                if (catalogue.grants[out.id].orEmpty().isNotEmpty()) {
                    add(Check(SECTION_WATCH, pendingRow("watch-grants-${out.id}", text(R.string.selftest_grants_title, out.label), SelfTestFix.InstallWatch)) { watchAccess(it, out) })
                }
            }
            for (stock in catalogue.stockAppsToDisable.filter { it.device == Device.Watch }) {
                add(Check(SECTION_WATCH, pendingRow("watch-stock-${stock.packageName}", text(R.string.selftest_stock_title, stock.label), SelfTestFix.DisableStock)) { watchStock(it, stock.packageName, stock.optional) })
            }
            if (outputs.any { it.id == BRIDGE_PHONE }) {
                add(Check(SECTION_LINK, pendingRow("link-bridge", text(R.string.selftest_bridge_link_title), SelfTestFix.InstallPhone)) { bridgeLink() })
            }
        }
    }

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
        return SelfTestUiState(titles.indices.mapNotNull { i -> rows[i]?.takeIf { it.isNotEmpty() }?.let { SelfTestSection(titles[i], it) } })
    }

    /** Runs the automated rows in parallel, writing each row's progress into [state]. Questions are left to the user. */
    suspend fun run(state: MutableStateFlow<SelfTestUiState>) = coroutineScope {
        val context = RunContext(watch)
        for (check in checks) {
            launch {
                val id = check.row.id
                state.update { it.withRow(id) { row -> row.copy(status = CheckStatus.Running, detail = null) } }
                val outcome = execute(check, context)
                state.update { it.withRow(id) { row -> row.copy(status = outcome.status, detail = outcome.detail, fix = outcome.fix ?: row.fix) } }
            }
        }
    }

    private suspend fun execute(check: Check, context: RunContext): Outcome = try {
        check.run(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        if (check.section == SECTION_WATCH) Outcome(CheckStatus.Warning, text(R.string.selftest_detail_watch_no_answer), SelfTestFix.WatchPair)
        else Outcome(CheckStatus.Warning, text(R.string.selftest_detail_error))
    } catch (e: RuntimeException) {
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
            .filter { it.kind != GrantKind.SecureSetting && it.kind != GrantKind.BatteryExemption }
            .map { GrantRow(it, CheckStatus.Pending) }
        val refreshed = withContext(io) { phoneGrants.refresh(pkg, rows) }
        val missing = refreshed.filter { it.status != CheckStatus.Passed && it.status != CheckStatus.Warning }.map { it.grant.name }
        val optional = refreshed.filter { it.status == CheckStatus.Warning }.map { it.grant.name }
        return accessOutcome(missing, optional, emptyList(), SelfTestFix.InstallPhone)
    }

    private suspend fun bridgeLink(): Outcome {
        val authority = prefix + BRIDGE_STATUS_AUTHORITY_SUFFIX
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
        return accessOutcome(missing, optional, unknown, SelfTestFix.InstallWatch)
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

    private companion object {
        const val BRIDGE_PHONE = "bridge-phone"
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
