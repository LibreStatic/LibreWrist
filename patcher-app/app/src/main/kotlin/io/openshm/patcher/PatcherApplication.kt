package io.openshm.patcher

import android.app.Application
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession

/** Loads the verified catalogue and the persisted setup before any screen runs. */
class PatcherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCatalogue.ensure(this)
        SetupSession.init(this)
    }
}
