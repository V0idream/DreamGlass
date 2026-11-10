package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NotificationLocalDismissContract as Policy
import org.junit.Assert.*
import org.junit.Test

class NotificationLocalDismissContractTest {
    enum class State {NOT_DISMISSED,DISMISSED,PARENT_DISMISSED}
    class Row {
        fun getAttachedChildren():List<Row> = emptyList()
        fun getEntryAdapter()=Adapter()
    }
    class Adapter {fun getEntry()=Entry()}
    class Entry {fun getRow()=Row();fun getDismissState()=State.NOT_DISMISSED;fun isClearable()=true}
    class Collection {fun locallyDismissNotifications(entries:List<Entry>,parent:Boolean) {entries.size;parent.hashCode()}}
    class Wrong {fun locallyDismissNotifications(entries:List<Entry>,parent:Boolean)=entries.size+parent.hashCode()}
    class Stack {
        fun clearNotifications(selection:Int,close:Boolean,headers:Boolean) {selection.hashCode();close.hashCode();headers.hashCode()}
        companion object {@JvmStatic fun includeChildInClearAll(row:Row,selection:Int):Boolean=row.hashCode()!=selection}
    }
    class ChangedStack {
        fun clearNotifications(selection:Int,close:Boolean) {selection.hashCode();close.hashCode()}
        companion object {@JvmStatic fun includeChildInClearAll(row:Row,selection:Int):Boolean=row.hashCode()!=selection}
    }
    @Test fun stockCollectionCommitIsMatchedButChangedReturnTypeIsRejected() {
        assertNotNull(Policy.resolve(Collection::class.java,Entry::class.java,Row::class.java))
        assertNull(Policy.resolve(Wrong::class.java,Entry::class.java,Row::class.java))
    }
    @Test fun clearableChildOrTopLevelRowCanCommitWithoutGroupRelationship() {
        // Native acceptance suffices. No synthetic retained-parent or sibling
        // booleans may reject same-package notifications shown as top-level rows.
        assertTrue(Policy.accepted(true,"NOT_DISMISSED","DISMISSED"))
        assertTrue(Policy.accepted(true,"NOT_DISMISSED","PARENT_DISMISSED"))
    }
    @Test fun ongoingEntryOrSummaryIsNotAnimatedEvenWhenNativeStateChanges() {
        assertFalse(Policy.accepted(false,"NOT_DISMISSED","DISMISSED"))
        assertFalse(Policy.accepted(false,"NOT_DISMISSED","PARENT_DISMISSED"))
    }
    @Test fun rejectedAndAlreadyDismissedEntriesDoNotProduceParticles() {
        assertFalse(Policy.accepted(true,"NOT_DISMISSED","NOT_DISMISSED"))
        assertFalse(Policy.accepted(true,"DISMISSED","DISMISSED"))
        assertFalse(Policy.accepted(true,"PARENT_DISMISSED","DISMISSED"))
        assertFalse(Policy.accepted(true,"NOT_DISMISSED",null))
    }
    @Test fun acceptedSummaryDeduplicatesChildrenButOngoingParentDoesNot() {
        class Node(val parent:Node?)
        val ongoing=Node(null);val ordinary=Node(ongoing)
        assertFalse(Policy.coveredByAncestor(ordinary,{it.parent}) {false})
        assertTrue(Policy.coveredByAncestor(ordinary,{it.parent}) {it===ongoing})
        val topLevel=Node(null)
        assertFalse(Policy.coveredByAncestor(topLevel,{it.parent}) {true})
    }
    @Test fun clearPreparationRequiresExactSelectionAndNativeInclusionSignatures() {
        val route=Policy.prepareRoute(Stack::class.java,Row::class.java)
        assertNotNull(route)
        assertEquals(listOf(Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType),route!!.clear.parameterTypes.toList())
        assertEquals(Adapter::class.java,route.adapter.returnType)
        assertEquals(Entry::class.java,route.entry.returnType)
        assertNull(Policy.prepareRoute(ChangedStack::class.java,Row::class.java))
    }
    class NativeCompletion:java.util.function.Consumer<Any> {
        @JvmField var owner:Stack?=null
        @JvmField var rows=java.util.ArrayList<Row>()
        @JvmField var selection=0
        override fun accept(value:Any) {value.hashCode()}
    }
    class ChangedCompletion:java.util.function.Consumer<Any> {
        @JvmField var owner:Stack?=null
        @JvmField var rows=java.util.ArrayList<Row>()
        @JvmField var selection=0
        @JvmField var unknownFlag=false
        override fun accept(value:Any) {value.hashCode()}
    }
    @Test fun sharedCompletionMustMatchVerifiedOwnerRowsAndSelectionLayout() {
        assertNotNull(Policy.completionRoute(NativeCompletion::class.java,Stack::class.java))
        assertNull(Policy.completionRoute(ChangedCompletion::class.java,Stack::class.java))
        assertNull(Policy.completionRoute(NativeCompletion::class.java,ChangedStack::class.java))
    }
    @Test fun nativeCompletionRunsOnceAcrossNoCloseAndPostCollapseWithReentry() {
        val once=Policy.OnceCompletion()
        var nativeDeletes=0
        fun nativeCallback() {
            if(once.claim()) {
                nativeDeletes++
                // Native events can synchronously re-enter during list rebuild.
                if(once.claim())nativeDeletes++
            }
        }
        nativeCallback() // the native no-close branch
        nativeCallback() // same instance retained by post-collapse action
        assertEquals(1,nativeDeletes)
    }
    @Test fun partialNativeFailureMustNotRepeatPotentialDeletionSideEffects() {
        val once=Policy.OnceCompletion()
        var sideEffects=0
        runCatching {if(once.claim()) {sideEffects++;error("native partial completion")}}
        if(once.claim())sideEffects++
        assertEquals(1,sideEffects)
        // A later, independent clear retains its own completion.
        assertTrue(Policy.OnceCompletion().claim())
    }

    @Test fun nativePostedCompletionIsRetainedWhenCollapseReplaysTheConsumer() {
        val once=Policy.OnceCompletion()
        val mainQueue=java.util.ArrayDeque<()->Unit>()
        var commits=0
        // Stock Lambda5(true) schedules, rather than performs, the collection call.
        if(once.claim())mainQueue.add {commits++}
        assertEquals(0,commits)
        // The original registered post-collapse action must not enqueue another.
        if(once.claim())mainQueue.add {commits++}
        while(mainQueue.isNotEmpty())mainQueue.removeFirst().invoke()
        assertEquals(1,commits)
    }

}
