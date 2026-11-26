package com.coloros16.liquidglass.diagnostics

import com.coloros16.liquidglass.diagnostics.ComponentApkCatalog.Match
import org.junit.Assert.*
import org.junit.Test

class TroubleshootingTest {
    @Test fun exactApkFingerprintsAreRequired() {
        val launcher = Troubleshooting.LAUNCHER
        val ota = "c1b3af973783ec014f7e32448b6087e143343d176051927ccd30e50d73c6db04"
        val previous = "ae500e951eb162f44805fba1180491dfecfac7f3945fa932e5d68b9a8b3394cc"
        val match = ComponentApkCatalog::match
        assertEquals(Match.EXACT, match(launcher, "17.5.5", 170050005, ota, 0))
        assertEquals(Match.EXACT, match(launcher, "17.5.5", 170050005, previous, 0))
        assertEquals(Match.SPLITS_UNVERIFIED, match(launcher, "17.5.5", 170050005, ota, 1))
        assertEquals(Match.SAME_VERSION_DIFFERENT_APK, match(launcher, "17.5.5", 170050005, "0".repeat(64), 0))
        assertEquals(Match.UNLISTED, match(launcher, "17.5.6", 170050006, ota, 0))
        assertEquals(Match.UNREADABLE, match(launcher, "17.5.5", 170050005, null, 0))
    }

    @Test fun sha256ReadsTheRawApkStream() {
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(digest, ComponentApkCatalog.sha256("abc".byteInputStream()))
        assertEquals(8, ComponentApkCatalog.samples.map { it.sha256 }.toSet().size)
    }

    @Test fun recommendedScopeRejectsCommentsAndSimilarPackageNames() {
        val text = "# com.android.systemui\ncom.android.launcherx; com.android.launcher # com.android.systemui"
        assertEquals(setOf(Troubleshooting.LAUNCHER), Troubleshooting.declaredTargets(text))
        assertEquals(setOf(Troubleshooting.LAUNCHER, Troubleshooting.SYSTEM_UI),
            Troubleshooting.declaredTargets("com.android.launcher, com.android.systemui"))
    }

    @Test fun declaredOverlapDoesNotOverrideKnownInactiveScope() {
        val launcher = setOf(Troubleshooting.LAUNCHER)
        val glass = Troubleshooting.GLASS
        val desk = Troubleshooting.DESK
        assertTrue(Troubleshooting.duplicate(glass, desk, emptySet(), launcher, activeKnown = false))
        assertFalse(Troubleshooting.duplicate(glass, desk, emptySet(), launcher, activeKnown = true))
        assertTrue(Troubleshooting.duplicate(desk, glass, launcher, emptySet(), activeKnown = true))
        assertFalse(Troubleshooting.duplicate(desk, "example.other.module", launcher, emptySet(), activeKnown = true))
    }
}
