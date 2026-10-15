package io.openshm.patcher.ui.screens.install

/**
 * Whether the primary footer button of an install step advances by itself when unattended:
 * Continue once done, and "Start installing" while there is no error. Retry, "Check again" and
 * "Continue anyway" never do.
 */
internal fun installFooterAuto(state: InstallUiState, phone: Boolean): Boolean = when {
    state.done -> true
    phone && state.canSkipGrants -> false
    else -> state.errorRes == null
}

/**
 * Whether an install step is waiting for the user, so an unattended run asks for attention: the
 * phone's Wireless debugging must be opened or its pairing code typed, installing apps must be
 * allowed, a signature conflict must be resolved, or (phone) access is still open after the install.
 * Errors are not waits: they end the run instead.
 */
internal fun installNeedsUser(state: InstallUiState, phone: Boolean): Boolean = when {
    state.selfAdb in SELF_ADB_USER_PHASES -> true
    state.running || state.selfAdb != null || state.errorRes != null -> false
    else -> state.needsUnknownSources || state.conflict != null || (phone && state.canSkipGrants)
}

private val SELF_ADB_USER_PHASES = setOf(
    SelfAdbPhase.WaitingForService,
    SelfAdbPhase.WaitingForCode,
    SelfAdbPhase.WrongCode,
    SelfAdbPhase.Ambiguous,
)
