package io.openshm.patcher.build

import android.content.Context
import java.io.File

/** Where builds live: finished APKs and their record are durable, the work directory is scratch. */
class BuildStorage(context: Context) {
    private val context = context.applicationContext
    val outputsDir: File get() = File(context.filesDir, "outputs")
    val workDir: File get() = File(context.cacheDir, "build")
    val record: BuiltOutputs get() = BuiltOutputs(File(context.filesDir, "built-outputs.properties"))
}
