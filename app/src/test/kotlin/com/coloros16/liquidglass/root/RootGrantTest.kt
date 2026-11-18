package com.coloros16.liquidglass.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGrantTest {
    @Test fun onlyKnownPromptlessVersionsCanProbeWithoutPastVerification() {
        assertFalse(RootGrant.preGrantOnlyVersion("31.0:MAGISKSU"))
        assertTrue(RootGrant.preGrantOnlyVersion("31.1:MAGISKSU"))
        assertTrue(RootGrant.preGrantOnlyVersion("31.1:MAGISK:R"))
        assertFalse(RootGrant.preGrantOnlyVersion("31.1:unknown"))
        assertFalse(RootGrant.preGrantOnlyVersion(""))
        assertTrue(RootGrant.reportsGrantedKernelSu("v3.2.0:KernelSU"))
        assertFalse(RootGrant.reportsGrantedKernelSu("Permission denied"))
        assertFalse(RootGrant.reportsGrantedKernelSu(":KernelSU"))
    }
}
