package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherFolderSurfacePolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class LauncherFolderSurfacePolicyTest {
    @Test fun largeFolderRequiresItsActualDedicatedBackgroundIdentity() {
        assertTrue(Policy.eligible(true,true,2,2))
        assertTrue(Policy.eligible(true,true,1,2))
        assertFalse(Policy.eligible(true,false,2,2))
        assertFalse(Policy.eligible(false,true,2,2))
    }
    @Test fun ordinaryFoldersAndInvalidWidgetSizedValuesStayOnTheirOriginalPath() {
        for(span in listOf(0 to 2,2 to 0,1 to 1,9 to 2,-1 to 2))
            assertFalse(Policy.eligible(true,true,span.first,span.second))
    }
}
