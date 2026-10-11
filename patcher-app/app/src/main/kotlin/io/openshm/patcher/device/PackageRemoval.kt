package io.openshm.patcher.device

/** What is installed under a package name, as far as the reinstall fallback is concerned. */
enum class PackageKind {
    /** Not installed (or not visible to this app). */
    Missing,

    /** A system app, or an updated system app: never offered for removal. */
    System,

    /** Installed by the user or an installer; the only kind the reinstall fallback may remove. */
    User,
}

/** The outcome of removing a package at the user's request. */
sealed interface UninstallResult {
    data object Success : UninstallResult

    /** [message] is the platform's own text (or the user's refusal), shown as detail. */
    data class Failure(val message: String) : UninstallResult
}
