package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NotificationLocalDismissContract as Policy
import org.junit.Assert.*
import org.junit.Test

class NotificationLocalDismissContractTest {
    enum class State {NOT_DISMISSED,DISMISSED,PARENT_DISMISSED}
    class Row
    class Entry {fun getRow()=Row();fun getDismissState()=State.NOT_DISMISSED;fun isClearable()=true}
    class Collection {fun locallyDismissNotifications(entries:List<Entry>,parent:Boolean) {entries.size;parent.hashCode()}}
    class Wrong {fun locallyDismissNotifications(entries:List<Entry>,parent:Boolean)=entries.size+parent.hashCode()}
    @Test fun stockCollectionCommitIsMatchedButChangedReturnTypeIsRejected() {
        assertNotNull(Policy.resolve(Collection::class.java,Entry::class.java,Row::class.java))
        assertNull(Policy.resolve(Wrong::class.java,Entry::class.java,Row::class.java))
    }
    @Test fun clearableChildOfMixedRetainedGroupCanCommitIndependently() {
        assertTrue(Policy.accepted(true,true,true,"NOT_DISMISSED","DISMISSED"))
        assertTrue(Policy.accepted(true,true,true,"NOT_DISMISSED","PARENT_DISMISSED"))
    }
    @Test fun ongoingChildrenAndRemovedWholeGroupsStayOnNativeRoutes() {
        assertFalse(Policy.accepted(false,true,true,"NOT_DISMISSED","DISMISSED"))
        assertFalse(Policy.accepted(true,false,true,"NOT_DISMISSED","DISMISSED"))
        assertFalse(Policy.accepted(true,true,false,"NOT_DISMISSED","DISMISSED"))
    }
    @Test fun rejectedAndAlreadyDismissedEntriesDoNotProduceParticles() {
        assertFalse(Policy.accepted(true,true,true,"NOT_DISMISSED","NOT_DISMISSED"))
        assertFalse(Policy.accepted(true,true,true,"DISMISSED","DISMISSED"))
        assertFalse(Policy.accepted(true,true,true,"NOT_DISMISSED",null))
    }
}
