package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.LauncherTiltSubmission
import org.junit.Assert.*
import org.junit.Test

class LauncherTiltSubmissionTest {
    @Test fun latestTargetPoseSurvivesLaterAffineAndIsConsumedOnce() {
        val queue=LauncherTiltSubmission<Any,Any>();val tx=Any();val s=Any()
        val first=FloatArray(16){1f};val last=FloatArray(16){2f}
        queue.put(tx,s,first,10);queue.put(tx,s,last,20);last[0]=99f
        assertTrue(queue.contains(tx,s));assertFalse(queue.contains(Any(),s))
        val poses=queue.take(tx,25);assertEquals(1,poses.size)
        assertSame(s,poses[0].surface.get());assertEquals(2f,poses[0].matrix[0],0f)
        assertTrue(queue.take(tx,26).isEmpty())
    }
    @Test fun mergeMovesPoseToAppliedTransactionAndSourceWins() {
        val queue=LauncherTiltSubmission<Any,Any>();val receiver=Any();val source=Any();val s=Any();val other=Any()
        queue.put(receiver,s,FloatArray(16){1f},10);queue.put(receiver,other,FloatArray(16){3f},10)
        queue.put(source,s,FloatArray(16){2f},20);queue.merge(receiver,source,21)
        assertTrue(queue.take(source,22).isEmpty())
        val poses=queue.take(receiver,22);assertEquals(2,poses.size)
        assertEquals(2f,poses.single { it.surface.get()===s }.matrix[0],0f)
        assertEquals(3f,poses.single { it.surface.get()===other }.matrix[0],0f)
    }
    @Test fun unrelatedCloseExpiredAndSelfMergedTransactionsRemainSafe() {
        val queue=LauncherTiltSubmission<Any,Any>();val tx=Any();val s=Any()
        queue.put(tx,s,FloatArray(16),1);queue.merge(tx,tx,2)
        queue.discard(Any());assertTrue(queue.contains(tx,s))
        assertTrue(queue.take(tx,242).isEmpty())
        queue.put(tx,s,FloatArray(16),300);queue.discard(tx);assertTrue(queue.take(tx,301).isEmpty())
    }
}
