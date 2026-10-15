package io.openshm.patcher.ui.screens.wearable

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.AppCatalogue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Looks at the phone for Galaxy Wearable, its watch plugin and a bonded watch. Nothing is changed;
 * [recheck] runs on entry, on resume (coming back from Galaxy Wearable) and on request.
 */
class WearablePairingViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(WearablePairingUiState())
    val state: StateFlow<WearablePairingUiState> = _state.asStateFlow()

    init {
        recheck()
    }

    fun onEvent(event: WearablePairingEvent) {
        when (event) {
            WearablePairingEvent.Recheck -> recheck()
            WearablePairingEvent.OpenGalaxyWearable -> open()
            WearablePairingEvent.GetGalaxyWearable -> store()
            // Needs an activity to ask; the host launches the request and calls recheck() after it.
            WearablePairingEvent.AllowBluetooth -> Unit
            // Navigation: the nav host handles it.
            WearablePairingEvent.Troubleshoot -> Unit
        }
    }

    private val companion get() = AppCatalogue.current.platform.companionApp

    fun recheck() {
        viewModelScope.launch(Dispatchers.Default) { _state.value = wearableState(readWearableFacts(getApplication(), companion), companion) }
    }

    private fun open() {
        val context = getApplication<Application>()
        context.packageManager.getLaunchIntentForPackage(companion.packageName)
            ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The store app first, the store's web page when no store app handles `market:`. */
    private fun store() {
        val context = getApplication<Application>()
        try {
            context.startActivity(storeIntent("market://details?id=$companion.packageName"))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(storeIntent("https://play.google.com/store/apps/details?id=$companion.packageName"))
            } catch (e2: ActivityNotFoundException) {
                // No store and no browser: the how-to still names the app.
            }
        }
    }

    private fun storeIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
