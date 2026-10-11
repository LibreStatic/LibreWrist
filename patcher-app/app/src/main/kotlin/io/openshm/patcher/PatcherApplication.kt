package io.openshm.patcher

import android.app.Application
import io.openshm.patcher.build.BuildService
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.SelfAdbService

/** Loads the verified catalogue and the persisted setup before any screen runs. */
class PatcherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCatalogue.ensure(this)
        SetupSession.init(this)
        BuildService.clearStaleProgress(this)
        SelfAdbService.clearStale(this)
    }
}
