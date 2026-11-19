package com.coloros16.liquidglass.hook

import org.junit.Assert.*
import org.junit.Test

class IconLibraryRecoveryTest {
    @Test fun lockedOrUnavailableBootReadDoesNotBecomeAnEmptyCatalog() {
        val recovery = IconLibraryRecovery<List<String>>()
        assertFalse(recovery.accept(null))
        assertNull(recovery.snapshot)
        assertTrue(recovery.accept(listOf("downloaded", "user-image")))
        assertEquals(listOf("downloaded", "user-image"), recovery.snapshot)
        assertFalse(recovery.accept(listOf("downloaded", "user-image")))
    }

    @Test fun failedRefreshPreservesImportsButAuthoritativeEmptyCatalogReplacesThem() {
        val recovery = IconLibraryRecovery<Set<String>>()
        assertTrue(recovery.accept(setOf("previous")))
        assertFalse(recovery.accept(null))
        assertEquals(setOf("previous"), recovery.snapshot)
        assertTrue(recovery.accept(emptySet()))
        assertEquals(emptySet<String>(), recovery.snapshot)
    }

    @Test fun retriesAreBoundedAndRenewedOnUnlock() {
        val recovery = IconLibraryRecovery<String>()
        assertNull(recovery.retryDelay(false))
        val delays = List(6) { recovery.retryDelay(true) }
        assertEquals(listOf(250L, 750L, 1500L, 3000L, 5000L, null), delays)
        assertNull(recovery.retryDelay(true))
        recovery.resetBudget()
        assertEquals(250L, recovery.retryDelay(true))
    }
}
