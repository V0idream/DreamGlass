package com.coloros16.liquidglass.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconGlassSuggestionContractTest {
    class Item(@JvmField val itemType: Int, @JvmField val mCardType: Int)
    open class LegacyCard(private val item: Any) {
        fun getItemInfo() = item
    }
    class CurrentCard(item: Item) : LegacyCard(item) {
        fun isSuggestCardNoBg() = false
    }
    class LauncherCardUtil {
        companion object {
            @JvmStatic fun isSuggestCard(item: Any) = item is Item && item.mCardType in setOf(82, 83)
        }
    }

    @Test fun legacyCardUsesLauncherClassifierWithoutRegisteringOtherCards() {
        val classifier = LauncherCardUtil::class.java.getMethod("isSuggestCard", Any::class.java)
        assertTrue(IconGlassSuggestionContract.matches(LegacyCard(Item(100, 82)), classifier))
        assertTrue(IconGlassSuggestionContract.matches(LegacyCard(Item(100, 83)), classifier))
        assertFalse(IconGlassSuggestionContract.matches(LegacyCard(Item(100, 81)), classifier))
        assertFalse(IconGlassSuggestionContract.matches(LegacyCard(Any()), classifier))
        assertFalse(IconGlassSuggestionContract.matches(CurrentCard(Item(100, 82)), classifier))
        assertTrue(IconGlassSuggestionContract.matches(LegacyCard(Item(100, 83))))
    }
}
