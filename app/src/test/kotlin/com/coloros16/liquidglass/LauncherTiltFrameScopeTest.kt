package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherTiltFrameScope
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LauncherTiltFrameScopeTest {
    @Test fun submitsOnceAfterLastNativeWriteAndKeepsReturn() {
        val scope=LauncherTiltFrameScope();val tx=Any();val surface=Any();val order=mutableListOf<String>()
        val result=scope.run(tx,surface,{
            order.add("native matrix 1");assertTrue(scope.matrixWritten(tx,surface))
            order.add("native matrix 2");assertTrue(scope.matrixWritten(tx,surface))
            order.add("native crop and alpha");42
        },{order.add("tilt")})
        assertEquals(42,result)
        assertEquals(listOf("native matrix 1","native matrix 2","native crop and alpha","tilt"),order)
        assertFalse(scope.matrixWritten(tx,surface))
    }
    @Test fun neverBorrowsSiblingOrAnotherTransactionsPose() {
        val scope=LauncherTiltFrameScope();val tx=Any();val s=Any();var submitted=false
        scope.run(tx,s,{
            assertFalse(scope.matrixWritten(tx,Any()))
            assertFalse(scope.matrixWritten(Any(),s))
        },{submitted=true})
        assertFalse(submitted)
    }
    @Test fun nestedTargetRestoresOuterScope() {
        val scope=LauncherTiltFrameScope();val tx=Any();val first=Any();val second=Any();val order=mutableListOf<Int>()
        scope.run(tx,first,{
            assertTrue(scope.matrixWritten(tx,first))
            scope.run(tx,second,{
                assertFalse(scope.matrixWritten(tx,first));assertTrue(scope.matrixWritten(tx,second))
            },{order.add(2)})
            assertTrue(scope.matrixWritten(tx,first))
        },{order.add(1)})
        assertEquals(listOf(2,1),order)
    }
    @Test fun originalExceptionIsNotRetriedAndNeverSubmits() {
        val scope=LauncherTiltFrameScope();val tx=Any();val s=Any();val expected=IllegalStateException("native")
        var count=0;var submitted=false
        try { scope.run(tx,s,{count++;scope.matrixWritten(tx,s);throw expected},{submitted=true});fail() }
        catch(actual:IllegalStateException) { assertSame(expected,actual) }
        assertEquals(1,count);assertFalse(submitted);assertFalse(scope.matrixWritten(tx,s))
    }
    @Test fun failedSubmissionDoesNotLeakAFrame() {
        val scope=LauncherTiltFrameScope();val tx=Any();val s=Any()
        try { scope.run(tx,s,{scope.matrixWritten(tx,s)},{throw IllegalStateException("bridge")});fail() }
        catch(_:IllegalStateException) { }
        assertFalse(scope.matrixWritten(tx,s))
    }
    @Test fun actualThreadScopeHasNoFortyMillisecondExpiry() {
        val scope=LauncherTiltFrameScope();val tx=Any();val s=Any();var submitted=false
        scope.run(tx,s,{Thread.sleep(65);assertTrue(scope.matrixWritten(tx,s))},{submitted=true})
        assertTrue(submitted)
    }
    @Test fun animationThreadsCannotOverwriteEachOthersFrame() {
        val scope=LauncherTiltFrameScope();val tx=Any();val s=Any()
        val ready=CountDownLatch(2);val go=CountDownLatch(1);val submissions=AtomicInteger();val failure=AtomicReference<Throwable>()
        val threads=(1..2).map {
            Thread {
                try { scope.run(tx,s,{
                    ready.countDown();check(go.await(2,TimeUnit.SECONDS));assertTrue(scope.matrixWritten(tx,s))
                },{submissions.incrementAndGet()}) }
                catch(t:Throwable) { failure.compareAndSet(null,t) }
            }.also { it.start() }
        }
        assertTrue(ready.await(2,TimeUnit.SECONDS));go.countDown();threads.forEach { it.join(2500) }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(2,submissions.get());assertFalse(scope.matrixWritten(tx,s))
    }
}
