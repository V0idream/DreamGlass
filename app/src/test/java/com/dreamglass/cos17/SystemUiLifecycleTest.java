package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.SystemUiLifecycle;
import com.coloros16.liquidglass.hook.SystemUiInstallGate;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemUiLifecycleTest {
    @Test public void modernImplementationOwnsTheChosenLifecycleMethod() throws Exception {
        assertEquals("com.android.systemui.application.impl.SystemUIApplicationImpl",
            SystemUiLifecycle.INSTANCE.resolveOnCreate(getClass().getClassLoader()).getDeclaringClass().getName());
    }
    @Test public void olderHostsUseTheirDeclaredApplicationLifecycle() throws Exception {
        ClassLoader legacy = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.android.systemui.application.impl.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        assertEquals("com.android.systemui.SystemUIApplication",
            SystemUiLifecycle.INSTANCE.resolveOnCreate(legacy).getDeclaringClass().getName());
    }
    @Test public void repeatedAndReentrantStartupDoesNotInstallDuplicateHooks() {
        SystemUiInstallGate gate = new SystemUiInstallGate();
        int[] installs = {0};
        assertTrue(gate.installOnce(() -> {
            installs[0]++;
            assertFalse(gate.installOnce(() -> { installs[0]++; return Unit.INSTANCE; }));
            return Unit.INSTANCE;
        }));
        assertFalse(gate.installOnce(() -> { installs[0]++; return Unit.INSTANCE; }));
        assertEquals(1, installs[0]);
    }
    @Test public void failedInitializationCanRetryWithoutLeavingGateLocked() {
        SystemUiInstallGate gate = new SystemUiInstallGate();
        RuntimeException failure = new RuntimeException("initialization failed");
        assertSame(failure, assertThrows(RuntimeException.class,
            () -> gate.installOnce(() -> { throw failure; })));
        assertTrue(gate.installOnce(() -> Unit.INSTANCE));
    }
}
