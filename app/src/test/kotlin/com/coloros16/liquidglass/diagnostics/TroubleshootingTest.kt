package com.coloros16.liquidglass.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TroubleshootingTest {
    @Test fun exactVersionPairsAreRequired() {
        assertTrue(Troubleshooting.knownVersion(Troubleshooting.LAUNCHER, "17.5.5", 170050005))
        assertTrue(Troubleshooting.knownVersion(Troubleshooting.SYSTEM_UI, "17.99.02", 179902))
        assertFalse(Troubleshooting.knownVersion(Troubleshooting.LAUNCHER, "17.5.5", 170050006))
        assertFalse(Troubleshooting.knownVersion(Troubleshooting.SYSTEM_UI, "17.99.03", 179902))
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
