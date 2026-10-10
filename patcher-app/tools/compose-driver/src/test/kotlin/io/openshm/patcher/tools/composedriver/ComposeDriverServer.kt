package io.openshm.patcher.tools.composedriver

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jdemeulenaere.compose.driver.startComposeDriverServer
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Serves the composable named by `compose.driver.composable` until the process is killed. A no-op
 * when that property is absent, so a plain unit-test run finishes immediately.
 *
 * The default viewport approximates a Pixel 8 (412 x 915 dp at 420 dpi). `compose.driver.qualifiers`
 * applies Robolectric qualifiers; a leading `+` layers them over the default (`+w360dp-h640dp-night`),
 * without it they replace the whole configuration, density included.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-420dpi")
class ComposeDriverServer {
    @Test
    fun serve() {
        val composable = System.getProperty("compose.driver.composable")
        Assume.assumeTrue("compose.driver.composable is not set", composable != null)
        System.getProperty("compose.driver.qualifiers")?.let(RuntimeEnvironment::setQualifiers)
        System.getProperty("compose.driver.fontScale")?.toFloat()?.let(RuntimeEnvironment::setFontScale)
        val port = System.getProperty("compose.driver.port")?.toInt() ?: 8137
        println("Compose Driver serving $composable on http://localhost:$port")
        startComposeDriverServer(contentComposableFullyQualifiedName = composable!!, port = port)
    }
}
