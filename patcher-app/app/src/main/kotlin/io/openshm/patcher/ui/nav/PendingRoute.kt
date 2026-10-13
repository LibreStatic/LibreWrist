package io.openshm.patcher.ui.nav

import android.content.Intent
import io.openshm.patcher.device.DeviceLogService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A route the activity was asked to open from outside the app (the device logs notification). The
 * activity posts it from its intent; the nav host navigates to it and then calls [consume].
 */
object PendingRoute {
    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    /** Reads the route named by [intent] and removes it from the intent, so a rotation does not open it again. */
    fun fromIntent(intent: Intent?) {
        val name = intent?.getStringExtra(DeviceLogService.EXTRA_OPEN_ROUTE) ?: return
        intent.removeExtra(DeviceLogService.EXTRA_OPEN_ROUTE)
        if (name == DeviceLogService.OPEN_ROUTE_DEVICE_LOGS) _route.value = Route.DeviceLogs
    }

    fun consume() {
        _route.value = null
    }
}
