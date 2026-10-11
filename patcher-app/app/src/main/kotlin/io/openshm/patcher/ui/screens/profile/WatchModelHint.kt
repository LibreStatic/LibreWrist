package io.openshm.patcher.ui.screens.profile

/** What a paired Bluetooth device name says about the watch's Android version. */
sealed interface WatchModelHint {
    /** The name as the phone shows it. */
    val name: String

    /** Always ships Wear OS 5 or newer (Watch7, Watch8, Ultra, ...). */
    data class Certain(override val name: String) : WatchModelHint

    /** Can run either, depending on the installed update (Watch4/5/6, FE). */
    data class Depends(override val name: String) : WatchModelHint

    companion object {
        private val MODEL = Regex("galaxy\\s*watch\\s*(\\d+|ultra|fe)\\b", RegexOption.IGNORE_CASE)

        /** Null for anything that is not a Galaxy Watch4 or newer. */
        fun fromBluetoothName(name: String): WatchModelHint? {
            val model = MODEL.find(name)?.groupValues?.get(1)?.lowercase() ?: return null
            val number = model.toIntOrNull()
            return when {
                model == "ultra" -> Certain(name)
                model == "fe" -> Depends(name)
                number != null && number >= 7 -> Certain(name)
                number != null && number >= 4 -> Depends(name)
                else -> null
            }
        }

        /** The most informative hint of several paired devices: the first certain one, else the first dependent one. */
        fun best(names: List<String>): WatchModelHint? {
            val hints = names.mapNotNull(::fromBluetoothName)
            return hints.firstOrNull { it is Certain } ?: hints.firstOrNull()
        }
    }
}
