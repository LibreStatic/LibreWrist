package io.openshm.patcher.revert

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.screens.revert.RevertPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.openshm.patcher.TestCatalogue

class RevertPlanTest {
    @Before fun catalogue() = TestCatalogue.install()

    private val outputs get() = AppCatalogue.current.outputs

    @Test
    fun candidatesCoverEveryOutputOfEveryProfileWhetherOrNotItWasBuilt() {
        val packages = RevertPlan.candidates(outputs, listOf(PREFIX)).map { it.pkg }.toSet()
        for (out in outputs) assertTrue(out.id, out.packageName(PREFIX) in packages)
        // Samsung Health and the alarm fork are candidates even though a profile may omit them.
        SHEALTH?.let { assertTrue(it in packages) }
        assertTrue(outputPkg("alarm-fork") in packages)
    }

    @Test
    fun aPrefixFoundOnTheWatchAddsCandidatesForEveryOutput() {
        val old = "io.openshm.uabcdef"
        val prefixes = RevertPlan.prefixes(PREFIX, outputs, listOf("com.android.settings", outputPkg("bridge-watch", old)), emptyList())
        assertEquals(listOf(PREFIX, old), prefixes)
        val packages = RevertPlan.candidates(outputs, prefixes).map { it.pkg }
        assertTrue(outputPkg("alarm-fork", old) in packages)
        assertTrue(outputPkg("bridge-phone", old) in packages)
    }

    @Test
    fun aPrefixFoundInThePhoneListenersAddsCandidates() {
        val old = "io.openshm.uabcdef"
        val prefixes = RevertPlan.prefixes(PREFIX, outputs, emptyList(), listOf("com.android.something", outputPkg("bridge-phone", old)))
        assertEquals(listOf(PREFIX, old), prefixes)
    }

    @Test
    fun aVendorPackageThatEndsLikeOursNeverYieldsAPrefix() {
        // The stock Samsung Health ends in ".shealth" like our Samsung Health+ output: it must not make
        // "com.sec.android.app" a prefix, or the stock app would become a removal candidate.
        val prefixes = RevertPlan.prefixes(PREFIX, outputs, listOf("com.samsung.x.shmbridge.watch"), listOf("com.sec.android.app.shealth"))
        assertEquals(listOf(PREFIX), prefixes)
        assertFalse("com.sec.android.app.shealth" in RevertPlan.candidates(outputs, prefixes).map { it.pkg })
    }

    @Test
    fun theStoredPrefixCountsEvenWhenNothingMatchesAndABlankOneDoesNot() {
        assertEquals(listOf(PREFIX), RevertPlan.prefixes(PREFIX, outputs, emptyList(), emptyList()))
        assertTrue(RevertPlan.prefixes("", outputs, emptyList(), emptyList()).isEmpty())
        assertTrue(RevertPlan.prefixes(null, outputs, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun packagesThatOnlyEndLikeOursButHaveNoPrefixAreIgnored() {
        val bare = RevertPlan.prefixes(null, outputs, listOf(".shmbridge.watch", "bad prefix.shmbridge.watch"), emptyList())
        assertTrue(bare.isEmpty())
    }

    @Test
    fun outputsWithoutThePlaceholderAppearOncePerDeviceNoMatterHowManyPrefixes() {
        val candidates = RevertPlan.candidates(outputs, listOf(PREFIX, "io.openshm.uabcdef"))
        val shm = outputPkg("shm-watch")
        assertEquals(1, candidates.count { it.pkg == shm && it.device == Device.Watch })
        assertEquals(1, candidates.count { it.pkg == shm && it.device == Device.Phone }.coerceAtMost(1))
        assertEquals(candidates.size, candidates.map { it.device to it.pkg }.toSet().size)
    }

    @Test
    fun theExcludedPackageIsNeverACandidate() {
        val candidates = RevertPlan.candidates(outputs, listOf(PREFIX), exclude = setOf(outputPkg("bridge-phone")))
        assertFalse(candidates.any { it.pkg == outputPkg("bridge-phone") })
    }
}
