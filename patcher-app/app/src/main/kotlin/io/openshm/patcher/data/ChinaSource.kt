package io.openshm.patcher.data

/** Whether China mode is on now: the user's choice when there is one, the region detection otherwise. */
fun effectiveChinaMode(): Boolean = Automation.state.value.chinaModeActive(Region.detected)

/**
 * The input [OutputApp] is built from: its `chinaFrom` input when [chinaMode] is on and it has one, its
 * `from` input otherwise (null for an app built from our own code). Everything that acquires, checks
 * or builds from the official APK goes through this; things that only need to know whether the app is
 * built from an official APK at all read [OutputApp.inputId].
 */
fun OutputApp.sourceInputId(chinaMode: Boolean): String? = if (chinaMode) chinaInputId ?: inputId else inputId
