package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.ButtonBackdropBlur;
import com.coloros16.liquidglass.hook.RuntimeWorkPolicy;
import kotlin.Unit;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RuntimeWorkPolicyTest {
    private final RuntimeWorkPolicy policy = RuntimeWorkPolicy.INSTANCE;

    @Test public void defaultChangeModeCapsMotionAndStillWorkIndependentlyOfConfiguredMax() {
        assertEquals(33, policy.captureIntervalMs(60, true, false));
        assertEquals(83, policy.captureIntervalMs(60, false, false));
        assertEquals(33, policy.captureIntervalMs(120, true, false));
        assertEquals(83, policy.captureIntervalMs(120, false, false));
        assertEquals(100, policy.captureIntervalMs(10, true, false));
    }
    @Test public void explicitImmediateModeRetainsItsPreviousRate() {
        assertEquals(16, policy.captureIntervalMs(60, true, true));
        assertEquals(33, policy.captureIntervalMs(60, false, true));
        assertEquals(8, policy.captureIntervalMs(120, true, true));
        assertEquals(16, policy.captureIntervalMs(120, false, true));
    }
    @Test public void corruptRatesCannotCreateBusyLoopOrDivideByZero() {
        for (int hz : new int[]{Integer.MIN_VALUE, -1, 0, 1, 30, 60, 120, Integer.MAX_VALUE})
            for (boolean moving : new boolean[]{false, true})
                for (boolean immediate : new boolean[]{false, true}) {
                    long delay = policy.captureIntervalMs(hz, moving, immediate);
                    assertTrue(delay >= 4 && delay <= 1000);
                }
    }
    @Test public void moduleAndErrorReporterExceptionsBothRemainContained() {
        AtomicInteger failures = new AtomicInteger();
        assertFalse(policy.guarded(e -> { failures.incrementAndGet(); throw new IllegalStateException("logger"); },
            () -> { throw new IllegalArgumentException("task"); }));
        assertEquals(1, failures.get());
        assertTrue(policy.guarded(e -> { throw new AssertionError(); }, () -> Unit.INSTANCE));
    }
    @Test public void memoryPressureBacksOffLongerThanTransientFailure() {
        assertEquals(30000, policy.failureDelayMs(new OutOfMemoryError()));
        assertEquals(2000, policy.failureDelayMs(new IllegalStateException()));
    }
    @Test public void injectedOutOfMemoryDoesNotEscapeToHostThread() {
        AtomicInteger failures = new AtomicInteger();
        assertFalse(policy.guarded(e -> { assertTrue(e instanceof OutOfMemoryError); failures.incrementAndGet(); return Unit.INSTANCE; },
            () -> { throw new OutOfMemoryError("synthetic fault, no large allocation"); }));
        assertEquals(1, failures.get());
    }
    @Test public void serialExecutorReusesThreadBoundsBacklogAndResumesAfterRejection() throws Exception {
        ThreadPoolExecutor executor = policy.executor("DreamGlass-test");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        java.util.Set<Long> threads = ConcurrentHashMap.newKeySet();
        try {
            executor.execute(() -> { threads.add(Thread.currentThread().getId()); entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { threads.add(Thread.currentThread().getId()); done.countDown(); });
            try { executor.execute(() -> {}); fail("Expected bounded backlog"); } catch (RejectedExecutionException expected) { }
            assertEquals(1, executor.getQueue().size());
            release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, threads.size());
            assertEquals(1, executor.getMaximumPoolSize());
            assertTrue(executor.allowsCoreThreadTimeOut());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void blurCancellationAbandonsWorkBeforeAllocationAndBetweenPasses() {
        int[] pixels = new int[100];
        try { ButtonBackdropBlur.INSTANCE.applyPremultiplied(pixels, 10, 10, 8f, () -> true); fail(); }
        catch (CancellationException expected) { }
        AtomicInteger checkpoints = new AtomicInteger();
        try { ButtonBackdropBlur.INSTANCE.applyPremultiplied(pixels, 10, 10, 8f, () -> checkpoints.incrementAndGet() >= 3); fail(); }
        catch (CancellationException expected) { }
        assertEquals(3, checkpoints.get());
        assertArrayEquals(new int[100], pixels);
    }
}
