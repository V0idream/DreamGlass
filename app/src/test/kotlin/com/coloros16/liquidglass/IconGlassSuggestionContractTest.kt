package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassSuggestionContract
import org.junit.Assert.*
import org.junit.Test

class IconGlassSuggestionContractTest {
    class Recommendation {fun isSuggestCardNoBg()=true}
    class OtherWidget {fun isSuggestCardNoBg()=false;fun getMSuggestNoPaddingViewWrapper()=Any()}
    class Wrapped {fun getMSuggestNoPaddingViewWrapper()=Any()}
    class Info(@JvmField val itemType:Int,@JvmField val mCardType:Int)
    class Legacy(val info:Info) {fun getItemInfo()=info}
    @Test fun nativeSemanticDoesNotDependOnOldNumericIds() {
        assertTrue(IconGlassSuggestionContract.matches(Recommendation()))
        assertTrue(IconGlassSuggestionContract.matches(Wrapped()))
    }
    @Test fun unrelatedWidgetsAndArbitraryObjectsStayNative() {
        assertFalse(IconGlassSuggestionContract.matches(OtherWidget()))
        assertFalse(IconGlassSuggestionContract.matches(Any()))
        assertFalse(IconGlassSuggestionContract.matches(Legacy(Info(100,99))))
    }
    @Test fun legacyKnownCardsRemainCompatible() {
        assertTrue(IconGlassSuggestionContract.matches(Legacy(Info(100,82))))
        assertFalse(IconGlassSuggestionContract.matches(Legacy(Info(1,82))))
    }
}
