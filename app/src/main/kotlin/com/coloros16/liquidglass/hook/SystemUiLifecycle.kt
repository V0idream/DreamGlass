package com.coloros16.liquidglass.hook

import java.lang.reflect.Method

/** COS17's application implementation owns onCreate; its public facade is not the lifecycle owner. */
object SystemUiLifecycle {
    fun resolveOnCreate(loader: ClassLoader): Method {
        for (name in arrayOf(
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
            "com.android.systemui.SystemUIApplication",
        )) {
            try {
                val method = Class.forName(name, false, loader).getDeclaredMethod("onCreate")
                if (method.returnType == Void.TYPE) return method
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            }
        }
        throw NoSuchMethodException("No declared SystemUI application onCreate()V")
    }
}

/** Normal startup can notify more than once; a failed install remains retryable. */
class SystemUiInstallGate {
    private var installed = false
    private var installing = false

    @Synchronized fun installOnce(install: () -> Unit): Boolean {
        if (installed || installing) return false
        installing = true
        try {
            install()
            installed = true
            return true
        } finally {
            installing = false
        }
    }
}
