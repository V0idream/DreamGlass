package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassPolicy as P
import org.junit.Assert.*
import org.junit.Test

class IconGlassPolicyTest {
    @Test fun missingAnyUserGateKeepsNative() {
        assertTrue(P.enabled(true,true,true,true))
        for(i in 0..3) {val b=BooleanArray(4){it!=i};assertFalse(P.enabled(b[0],b[1],b[2],b[3]))}
    }
    @Test fun workspaceAndDockAndVerifiedExtraHostsAreEligible() {
        for(container in listOf(-100L,-101L))assertTrue(P.eligible(0,container,true,true,false,false,false))
        for(display in listOf(-1,1,2,5,6,7,11,12,13))assertFalse(P.eligible(display,-100,true,true,false,false,false))
        for(container in listOf(-102L,0L,1L,100L))assertFalse(P.eligible(0,container,true,true,false,false,false))
        assertTrue(P.eligible(2,100L,true,true,false,false,false))
        assertTrue(P.eligible(0,100L,true,true,false,false,false,folderPreview=true))
        assertTrue(P.eligible(0,-102L,true,true,false,false,false,desktopHost=true))
        assertTrue(P.eligible(12,900L,true,true,false,false,false,suggestion=true))
        assertFalse(P.eligible(1,900L,true,true,false,false,true,suggestion=true))
    }
    @Test fun hiddenLockedAndPowerSaveDoNotRequestWork() {
        assertFalse(P.eligible(0,-100,false,true,false,false,false))
        assertFalse(P.eligible(0,-100,true,false,false,false,false))
        assertFalse(P.eligible(0,-100,true,true,true,false,false))
        assertFalse(P.eligible(0,-100,true,true,false,true,false))
        assertFalse(P.eligible(0,-100,true,true,false,false,true))
    }
    @Test fun ancestorMaskRejectsSharedDrawableConsumers() {
        listOf("AllAppsRecyclerView","FloatingIconView","DragView","RecentsView","DeepShortcutsContainer")
            .forEach {assertTrue(it,P.blockedParent("com.android.launcher3.$it"))}
        assertFalse(P.blockedParent("com.android.launcher3.ShortcutsAndWidgetsContainer"))
        assertFalse(P.blockedParent("com.android.launcher3.OplusHotseat"))
        assertFalse(P.blockedParent("com.android.launcher3.folder.FlexibleFolderIcon"))
        assertFalse(P.blockedParent("com.android.launcher3.folder.FolderContent"))
    }
    @Test fun malformedOrNonIconBoundsRemainNative() {
        assertTrue(P.validSize(144f,144f));assertFalse(P.validSize(0f,144f))
        assertFalse(P.validSize(Float.NaN,144f));assertFalse(P.validSize(2000f,2000f))
        assertFalse(P.validSize(144f,30f))
    }
}
