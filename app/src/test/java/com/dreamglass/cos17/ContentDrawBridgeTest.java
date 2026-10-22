package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.ContentDrawBridge;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContentDrawBridgeTest {
    /** Models the observed COS17 branch: a valid native shader can still lead to a placeholder. */
    static class NativeContent {
        boolean shaderAvailable = true;
        boolean imageAvailable = false;
        int placeholderDraws, shaderDraws, calls;
        final Object result = new Object();
        Object draw() {
            calls++;
            if (!imageAvailable) placeholderDraws++;
            else if (shaderAvailable) shaderDraws++;
            return result;
        }
    }
    @Test public void ownBackgroundRendersWithoutWaitingForMissingNativeImage() {
        NativeContent nativeContent = new NativeContent();
        int[] glassDraws = {0};
        assertNull(ContentDrawBridge.INSTANCE.dispatch(() -> { glassDraws[0]++; return true; },
            nativeContent::draw, e -> { fail(e.toString()); return Unit.INSTANCE; }));
        assertEquals(1, glassDraws[0]);
        assertEquals(0, nativeContent.calls);
        assertEquals(0, nativeContent.placeholderDraws);
    }
    @Test public void unavailableOwnSourceFallsBackExactlyOnceWithNativeResult() {
        NativeContent nativeContent = new NativeContent();
        assertSame(nativeContent.result, ContentDrawBridge.INSTANCE.dispatch(() -> false,
            nativeContent::draw, e -> Unit.INSTANCE));
        assertEquals(1, nativeContent.calls);
        assertEquals(1, nativeContent.placeholderDraws);
    }
    @Test public void moduleFailureIsReportedAndFallsBackWithoutBreakingSystemUI() {
        NativeContent nativeContent = new NativeContent();
        RuntimeException failure = new RuntimeException("own shader unavailable");
        Throwable[] observed = {null};
        assertSame(nativeContent.result, ContentDrawBridge.INSTANCE.dispatch(() -> { throw failure; },
            nativeContent::draw, e -> { observed[0] = e; return Unit.INSTANCE; }));
        assertSame(failure, observed[0]);
        assertEquals(1, nativeContent.calls);
    }
    @Test public void nativeExceptionIsNotSwallowedOrRetried() {
        RuntimeException failure = new RuntimeException("native failure");
        int[] calls = {0};
        assertSame(failure, assertThrows(RuntimeException.class,
            () -> ContentDrawBridge.INSTANCE.dispatch(() -> false,
                () -> { calls[0]++; throw failure; }, e -> Unit.INSTANCE)));
        assertEquals(1, calls[0]);
    }
}
