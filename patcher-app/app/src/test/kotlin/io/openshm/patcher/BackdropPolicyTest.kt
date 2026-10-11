package io.openshm.patcher

import io.openshm.patcher.ui.nav.BackdropPolicy
import io.openshm.patcher.ui.nav.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropPolicyTest {
    private fun BackdropPolicy.go(route: Route, id: String) = onDestination(route.path, id)

    @Test fun shownThroughTheLightStepsAndGoneAtBuild() {
        var p = BackdropPolicy().go(Route.Welcome, "w1").go(Route.Profile, "p1").go(Route.Customize, "c1")
        assertFalse(p.dispersed)
        p = p.go(Route.Build, "b1")
        assertTrue(p.dispersed)
    }

    @Test fun goingBackToEarlierStepsDoesNotBringItBack() {
        var p = BackdropPolicy().go(Route.Welcome, "w1").go(Route.Profile, "p1").go(Route.Customize, "c1").go(Route.Build, "b1")
        p = p.go(Route.Customize, "c1").go(Route.Profile, "p1").go(Route.Welcome, "w1")
        assertTrue(p.dispersed)
    }

    @Test fun aNewRunOfTheWizardBringsItBack() {
        var p = BackdropPolicy().go(Route.Welcome, "w1").go(Route.Build, "b1").go(Route.Home, "h1")
        assertTrue(p.dispersed)
        assertFalse(p.go(Route.Profile, "p2").dispersed)
        assertFalse(p.go(Route.Welcome, "w2").dispersed)
    }

    @Test fun laterScreensAndSideScreensKeepItAway() {
        val p = BackdropPolicy().go(Route.Home, "h1").go(Route.SigningKey, "k1").go(Route.SelfTest, "s1")
        assertTrue(p.dispersed)
    }

    @Test fun survivesSaveAndRestore() {
        val p = BackdropPolicy().go(Route.Welcome, "w1").go(Route.Build, "b1")
        val restored = with(BackdropPolicy.Saver) {
            restore(androidx.compose.runtime.saveable.SaverScope { true }.save(p)!!)
        }!!
        assertTrue(restored.dispersed)
        assertTrue(restored.go(Route.Welcome, "w1").dispersed)
    }
}
