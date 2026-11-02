package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.GalleryMaterialRequests
import com.coloros16.liquidglass.hook.GalleryMaterialRequests.Channel
import org.junit.Assert.*
import org.junit.Test

class GalleryMaterialRequestsTest {
    @Test fun neverSuppressNativeBeforeBothChannelsCanBeRestored() {
        val state=GalleryMaterialRequests<String>()
        assertFalse(state.begin())
        assertFalse(state.remember(Channel.BLUR,"native blur"))
        assertFalse(state.begin())
        assertFalse(state.active)
        assertTrue(state.end().isEmpty())
    }
    @Test fun activeNativeUpdatesAreSavedAndLatestValuesAreRestoredInOrder() {
        val state=GalleryMaterialRequests<String>()
        state.remember(Channel.BLUR,"blur1");state.remember(Channel.STROKE,"stroke1")
        assertTrue(state.begin())
        assertTrue(state.remember(Channel.STROKE,"stroke2"))
        assertTrue(state.remember(Channel.BLUR,"blur2"))
        assertEquals(listOf("blur2","stroke2"),state.end())
        assertFalse(state.active)
        assertTrue(state.end().isEmpty())
    }
    @Test fun aNewLeaseUsesLatestNativeConfigurationInsteadOfAnOldSnapshot() {
        val state=GalleryMaterialRequests<String>()
        state.remember(Channel.BLUR,"blur1");state.remember(Channel.STROKE,"stroke1")
        assertTrue(state.begin());state.end()
        assertFalse(state.remember(Channel.BLUR,"blur3"))
        assertTrue(state.begin())
        assertEquals(listOf("blur3","stroke1"),state.end())
    }
}
