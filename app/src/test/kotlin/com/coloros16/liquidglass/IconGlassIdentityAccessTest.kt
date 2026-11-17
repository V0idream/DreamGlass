package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.IconGlassIdentityAccess
import org.junit.Assert.*
import org.junit.Test

class IconGlassIdentityAccessTest {
    open class BaseItem { @JvmField var user="primary" }
    class AppItem:BaseItem() {
        var target="old.component"
        fun getTargetComponent()=target
    }
    class Shortcut {
        var shortcut="old.shortcut"
        fun getTargetComponent()="application"
        fun getDeepShortcutId()=shortcut
    }
    @Test fun missingShortcutAccessorIsCachedAcrossNormalAppFrames() {
        val first=IconGlassIdentityAccess.forType(AppItem::class.java)
        repeat(60) {
            assertSame(first,IconGlassIdentityAccess.forType(AppItem::class.java))
            assertNull(first.shortcut)
        }
        assertNotNull(first.component)
    }
    @Test fun reusedItemKeepsReadingCurrentComponentAndInheritedUser() {
        val item=AppItem();val access=IconGlassIdentityAccess.forType(item.javaClass)
        val component=checkNotNull(access.component);val user=checkNotNull(access.user)
        assertEquals("old.component",component.invoke(item))
        assertEquals("primary",user.get(item))
        item.target="new.component";item.user="secondary"
        assertEquals("new.component",component.invoke(item))
        assertEquals("secondary",user.get(item))
    }
    @Test fun shortcutsRetainTheirOwnChangingIdentity() {
        val item=Shortcut();val access=IconGlassIdentityAccess.forType(item.javaClass)
        val shortcut=checkNotNull(access.shortcut)
        assertEquals("old.shortcut",shortcut.invoke(item))
        item.shortcut="new.shortcut"
        assertEquals("new.shortcut",shortcut.invoke(item))
        assertNull(access.user)
    }
    @Test fun unrelatedObjectsDoNotTriggerRepeatedFailedLookups() {
        val access=IconGlassIdentityAccess.forType(String::class.java)
        assertNull(access.component);assertNull(access.user);assertNull(access.shortcut)
        assertSame(access,IconGlassIdentityAccess.forType(String::class.java))
    }
}
