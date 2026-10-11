package io.openshm.patcher

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.data.VariantChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The verified catalogue, mapped into the app model, must show what the screens always showed. */
class AppCatalogueTest {
    private val c = TestCatalogue.load()

    @Test
    fun serialAndInputs() {
        assertEquals(7L, c.serial)
        assertEquals(
            listOf(
                listOf("shm-phone", "Samsung Health Monitor (phone)", "1.4.6.001", "Phone",
                    "3f20656e8c2ea34b95acb0b4d006b8aab3210fcc37cfb16abd2a7e4b91ca8524", "com.samsung.android.shealthmonitor",
                    "samsung-electronics-co-ltd/samsung-health-monitor-2", "[ApkNotBundle, NoDpi, MinAndroid11]"),
                listOf("shm-watch", "Samsung Health Monitor (Wear OS)", "1.5.1.022", "Watch",
                    "a714e9a5950f456b8da4843bd6cdb19948a0c6fd9101b473e24beb3384a2c620", "com.samsung.android.shealthmonitor",
                    "samsung-electronics-co-ltd/samsung-health-monitor-wear-os", "[ApkNotBundle, NoDpi]"),
                listOf("alarm-watch", "Alarm (Wear OS)", "1.3.05.5", "Watch",
                    "fdbb5942a3b2a6c5b9c7c1c333a0e5e669732b1dc5a27f8b63e3b9c62c621151", "com.samsung.android.watch.alarm",
                    "samsung-electronics-co-ltd/alarm-wear-os", "[ApkNotBundle, NoDpi, MinAndroid14]"),
            ),
            c.inputs.map {
                listOf(it.id, it.label, it.versionName, it.device.name, it.sha256, it.packageName, it.apkMirrorSlug,
                    it.variantChips.toString())
            },
        )
        assertEquals(
            "https://www.apkmirror.com/apk/samsung-electronics-co-ltd/alarm-wear-os/",
            c.input("alarm-watch").mirrorUrl,
        )
        assertEquals(
            listOf(
                "https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-health-monitor-2/samsung-health-monitor-1-4-6-001-release/",
                "https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-health-monitor-wear-os/samsung-health-monitor-wear-os-1-5-1-022-release/",
                "https://www.apkmirror.com/apk/samsung-electronics-co-ltd/alarm-wear-os/alarm-wear-os-1-3-05-5-release/",
            ),
            c.inputs.map { io.openshm.patcher.data.ApkMirrorGuide.startUrl(it) },
        )
        assertEquals(listOf(true, true, true), c.inputs.map { it.onApkMirror })
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), c.input("shm-phone").abis)
    }

    @Test
    fun outputsInInstallOrder() {
        assertEquals(
            listOf(
                listOf("shm-phone", "Samsung Health Monitor (patched)", "Phone", "com.samsung.android.shealthmonitors", "shm-phone"),
                listOf("shm-watch", "Samsung Health Monitor for Wear OS (patched)", "Watch", "com.samsung.android.shealthmonitors", "shm-watch"),
                listOf("bridge-watch", "Bridge for the watch", "Watch", "\${PKG_PREFIX}.shmbridge.watch", "null"),
                listOf("alarm-fork", "Phone alarms on the watch", "Watch", "\${PKG_PREFIX}.shmbridge.alarm", "alarm-watch"),
                listOf("bridge-phone", "Bridge for the phone", "Phone", "\${PKG_PREFIX}.shmbridge.phone", "null"),
            ),
            c.outputs.map { listOf(it.id, it.label, it.device.name, it.packageTemplate, it.inputId.toString()) },
        )
    }

    @Test
    fun patchesKeepOrderTitlesAndInjections() {
        assertEquals(
            listOf("generic-manufacturer-check", "generic-vendor-check", "shm-min-age-22-to-16", "shm-supported-type-ordinals",
                "shm-region-supportedtype", "shm-region-countrylist", "shm-root-packagevalidator", "shm-force-update-response",
                "shm-force-update-pref", "shm-main-gates", "shm-setup-network-gate", "shm-sleep-apnea-support", "phone-rebrand-gms"),
            c.patches("shm-phone").map { it.id },
        )
        val watch = c.patches("shm-watch")
        assertEquals(10, watch.size)
        assertEquals("watch-mod", watch.last().id)
        assertEquals("Health Tracking module", watch.last().title)
        assertTrue(watch.last().injection)
        assertEquals("Adds the module that talks to the Health Tracking service.", watch.last().description)
        assertEquals("Manufacturer check", watch.first().title)
        assertFalse(watch.first().injection)
        val alarm = c.patches("alarm-watch")
        assertEquals(18, alarm.size)
        assertEquals("alarm-fork", alarm.last().id)
        assertEquals("Alarm link module", alarm.last().title)
        assertEquals(13 + 9 + 17, c.patternCount(listOf("shm-phone", "shm-watch", "alarm-watch")))
        assertTrue(c.patchSets.flatMap { it.patches }.all { it.description.isNotBlank() })
    }

    @Test
    fun grantsKeepKindsAndShortNames() {
        fun names(id: String) = c.grants.getValue(id).map { "${it.kind}:${it.name}:${it.optional}" }
        assertEquals(emptyList<String>(), names("shm-phone"))
        assertEquals(listOf("AppOp:SCHEDULE_EXACT_ALARM:false", "SecureSetting:doze_always_on:true"), names("shm-watch"))
        assertEquals(
            listOf("Permission:POST_NOTIFICATIONS:false", "Permission:BLUETOOTH_CONNECT:false", "Permission:READ_PHONE_STATE:false",
                "Permission:ANSWER_PHONE_CALLS:false", "AppOp:SCHEDULE_EXACT_ALARM:false", "AppOp:USE_FULL_SCREEN_INTENT:false",
                "AppOp:SYSTEM_ALERT_WINDOW:false", "AppOp:MANAGE_ONGOING_CALLS:false",
                "NotificationListener:.SamsungAlarmListener:false", "BatteryExemption:deviceIdleWhitelist:false"),
            names("bridge-watch"),
        )
        assertEquals(
            listOf("Permission:POST_NOTIFICATIONS:false", "AppOp:USE_FULL_SCREEN_INTENT:false", "BatteryExemption:deviceIdleWhitelist:true"),
            names("alarm-fork"),
        )
        assertEquals(7, c.grants.getValue("bridge-phone").size)
        assertEquals("permission", c.grants.getValue("bridge-watch").first().engine?.kind)
    }

    @Test
    fun stockApps() {
        val stock = c.stockAppsToDisable.single()
        assertEquals(Device.Watch, stock.device)
        assertEquals("com.samsung.android.shealthmonitor", stock.packageName)
        assertEquals("Samsung Health Monitor (stock)", stock.label)
        assertTrue(stock.optional)
        assertEquals("Two copies of the app would compete for the same sensor.", stock.reason)
    }

    @Test
    fun minimumAndroidChipThresholds() {
        fun chips(minSdk: Long) = io.openshm.patcher.data.LoadedCatalogue.chipsOf(mapOf("minSdk" to minSdk))
        assertEquals(emptyList<VariantChip>(), chips(30))
        assertEquals(listOf(VariantChip.MinAndroid11), chips(31))
        assertEquals(listOf(VariantChip.MinAndroid11), chips(33))
        assertEquals(listOf(VariantChip.MinAndroid14), chips(34))
        assertEquals(listOf(VariantChip.MinAndroid14), chips(36))
    }

    @Test
    fun grantKindsAreAllMapped() {
        assertEquals(
            setOf(GrantKind.Permission, GrantKind.AppOp, GrantKind.NotificationListener, GrantKind.BatteryExemption, GrantKind.SecureSetting),
            c.grants.values.flatten().map { it.kind }.toSet(),
        )
    }

    @Test
    fun aTamperedCatalogueFailsVerification() {
        val bytes = TestCatalogue.bytes
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        try {
            TestCatalogue.load(bytes = bytes)
            fail("expected a verification failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("failed verification"))
        }
    }

    @Test
    fun aCatalogueNeedingANewerAppIsRejected() {
        // minAppVersion in the repository catalogue is 1, so app version 0 is "too old".
        try {
            TestCatalogue.load(appVersionCode = 0)
            fail("expected a minAppVersion failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("needs app version"))
        }
    }
}
