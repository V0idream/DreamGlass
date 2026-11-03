package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NativeAnimationObserver
import org.junit.Assert.*
import org.junit.Test

class NativeAnimationObserverTest {
    @Test fun directNativeListenerIterationStillReachesDeletionAndRemainingCallbacks() {
        val queue=ArrayList<()->Unit>();val events=ArrayList<String>();val listeners=ArrayList<()->Unit>()
        val observer=NativeAnimationObserver({queue.add(it)}) {throw AssertionError(it)}
        lateinit var own:()->Unit
        own={observer.finish({events.add("particles-end")}, {listeners.remove(own);events.add("detach")})}
        listeners.add {events.add("native-before")};listeners.add(own)
        listeners.add {events.add("native-delete-tasks")}
        // Exact OEM pattern: no clone, no indexing snapshot.
        for(listener in listeners)listener()
        assertEquals(listOf("native-before","particles-end","native-delete-tasks"),events)
        assertEquals(3,listeners.size)
        queue.forEach {it()}
        assertEquals(2,listeners.size);assertEquals("detach",events.last())
    }
    @Test fun rejectedGpuWorkDoesNotPreventNativeDeletion() {
        var deleted=false;var failures=0
        val observer=NativeAnimationObserver({it()}) {failures++}
        val listeners=listOf<()->Unit>({observer.invoke {throw java.util.concurrent.RejectedExecutionException()}},{deleted=true})
        listeners.forEach {it()}
        assertTrue(deleted);assertEquals(1,failures)
    }
    @Test fun failedQueueNeverFallsBackToSynchronousListMutation() {
        var removed=false;var failures=0;var ended=false
        val observer=NativeAnimationObserver({throw IllegalStateException("queue unavailable")}) {failures++}
        observer.finish({ended=true},{removed=true})
        assertTrue(ended);assertFalse(removed);assertEquals(1,failures)
    }
    @Test fun cleanupStillQueuesWhenEffectEndOrErrorReportingFails() {
        val queue=ArrayList<()->Unit>();var detached=false
        val observer=NativeAnimationObserver({queue.add(it)}) {throw IllegalStateException("report")}
        observer.finish({error("visual failure")},{detached=true})
        assertFalse(detached);queue.forEach {it()};assertTrue(detached)
    }
}
