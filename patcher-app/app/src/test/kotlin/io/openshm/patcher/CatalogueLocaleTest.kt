package io.openshm.patcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.openshm.patcher.data.AppCatalogue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A language change while the process lives re-resolves the catalogue text on the next activity. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en")
class CatalogueLocaleTest {
    private fun label(id: String) = AppCatalogue.current.output(id).label

    @Test
    fun followLocalesSwitchesTheText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppCatalogue.adoptStored(context)
        assertEquals("Bridge for the phone", label("bridge-phone"))

        RuntimeEnvironment.setQualifiers("es")
        AppCatalogue.followLocales(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("Puente para el teléfono", label("bridge-phone"))

        RuntimeEnvironment.setQualifiers("en")
        AppCatalogue.followLocales(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("Bridge for the phone", label("bridge-phone"))
    }
}
