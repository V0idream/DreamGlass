package com.dreamglass.cos17;

import android.graphics.Rect;
import android.view.View;
import com.coloros16.liquidglass.hook.QsSeekbarCompat;
import org.junit.Test;
import static org.junit.Assert.*;

public class QsSeekbarCompatTest {
    public static class Modern {
        public Object getSeekBarActiveDrawable(View v, Rect r, float radius, float weight,
                boolean circle, boolean stroke, float amount) { return null; }
        public Object getSeekBarActiveDrawable(View v) { return null; }
    }
    public static class Legacy {
        public Object getSeekBarActiveDrawable(View v) { return null; }
    }
    public static class Unrelated {
        public Object getSeekBarActiveDrawable(View v, Rect r, int radius, float weight,
                boolean circle, boolean stroke, float amount) { return null; }
        public Object getSeekBarBackground(View v, Rect r, float radius, float weight,
                boolean circle, boolean stroke, float amount) { return null; }
    }

    @Test public void cos17SignatureTakesPriorityOverLegacy() throws Exception {
        assertArrayEquals(new Class<?>[]{View.class, Rect.class, float.class, float.class,
                boolean.class, boolean.class, float.class},
                new QsSeekbarCompat().resolveActiveFactory(Modern.class).getParameterTypes());
    }
    @Test public void olderSingleArgumentFactoryStillResolves() throws Exception {
        assertArrayEquals(new Class<?>[]{View.class},
                new QsSeekbarCompat().resolveActiveFactory(Legacy.class).getParameterTypes());
    }
    @Test public void unrelatedOverloadsAndBackgroundFactoryAreRejected() {
        assertThrows(NoSuchMethodException.class,
                () -> new QsSeekbarCompat().resolveActiveFactory(Unrelated.class));
    }
    @Test public void wrapperIsNotMistakenForTheRenderingTarget() {
        QsSeekbarCompat roles = new QsSeekbarCompat();
        Object wrapper = new Object(), target = new Object();
        roles.registerFactoryResult(wrapper);
        assertFalse(roles.isActiveTarget(wrapper));
        assertFalse(roles.isActiveTarget(target));
        assertTrue(roles.bind(wrapper, target));
        assertTrue(roles.isActiveTarget(target));
    }
    @Test public void backgroundLayerDoesNotInheritActiveTint() {
        QsSeekbarCompat roles = new QsSeekbarCompat();
        Object activeWrapper = new Object(), backgroundWrapper = new Object();
        Object activeTarget = new Object(), backgroundTarget = new Object();
        roles.registerFactoryResult(null);
        roles.registerFactoryResult(activeWrapper);
        assertTrue(roles.bind(activeWrapper, activeTarget));
        assertFalse(roles.bind(backgroundWrapper, backgroundTarget));
        assertFalse(roles.isActiveTarget(backgroundTarget));
    }
    @Test public void replacementPlatformDrawableGetsItsOwnActiveRole() {
        QsSeekbarCompat roles = new QsSeekbarCompat();
        Object wrapper = new Object(), first = new Object(), replacement = new Object();
        roles.registerFactoryResult(wrapper);
        roles.bind(wrapper, first);
        roles.bind(wrapper, replacement);
        assertTrue(roles.isActiveTarget(replacement));
    }
}
