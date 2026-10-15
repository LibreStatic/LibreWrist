package io.openshm.patcher.preview

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.OutputApp

/**
 * Outputs and inputs of the installed catalogue, picked by what they do rather than by id, so the
 * previews never name a catalogue entry. The names say which role a sample plays (the app that replaces a
 * stock app, the app that links to the companion app, ...), not which product it is.
 */
internal object SampleApps {
    private val catalogue get() = AppCatalogue.current
    private val extras get() = catalogue.watchExtraOutputs

    /** The phone app built from an official input (the first one). */
    val phoneMain: OutputApp get() = catalogue.outputs.first { it.device == Device.Phone && it.inputId != null }

    /** The watch app that replaces a stock app. */
    val watchMain: OutputApp get() = catalogue.outputs.first { it.id in extras }

    /** A watch app built from an official input that replaces nothing. */
    val watchOther: OutputApp get() = catalogue.outputs.first { it.device == Device.Watch && it.inputId != null && it.id !in extras }

    /** The watch app that links to the companion app. */
    val watchLinked: OutputApp get() = catalogue.outputs.first { it.companionLink }

    /** The phone app that carries the known signer. */
    val phoneSigner: OutputApp get() = catalogue.outputs.first { it.knownSigner }

    /** The last phone app built from an official input. */
    val phoneExtra: OutputApp get() = catalogue.outputs.last { it.device == Device.Phone && it.inputId != null }

    /** The official input [output] is built from. */
    fun input(output: OutputApp): InputApp = catalogue.input(requireNotNull(output.inputId))

    /** A package of [output] under an earlier prefix, as a leftover. */
    fun leftover(output: OutputApp): String = output.packageName("com.oldprefix")
}
