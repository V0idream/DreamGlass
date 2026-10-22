package com.coloros16.liquidglass.hook

/** Android 17 moved the legacy capture family to ScreenCaptureInternal.
 * Keep every argument/listener in one verified family; never mix public/new and internal types.
 */
object CaptureApi {
    private const val LEGACY = "android.window.ScreenCapture"
    @Volatile private var resolved: String? = null

    fun load(name: String, loader: ClassLoader?): Class<*> {
        if (name != LEGACY && !name.startsWith(LEGACY + "$")) {
            return if (loader != null) Class.forName(name, false, loader) else Class.forName(name)
        }
        val root = resolved ?: synchronized(this) {
            resolved ?: resolve(loader).also { resolved = it }
        }
        return Class.forName(root + name.removePrefix(LEGACY), false, loader)
    }

    private fun resolve(loader: ClassLoader?): String {
        for (root in listOf("android.window.ScreenCaptureInternal", LEGACY)) {
            try {
                val type = Class.forName(root, false, loader)
                Class.forName(root + "\$CaptureArgs\$Builder", false, loader).getMethod("build")
                Class.forName(root + "\$DisplayCaptureArgs\$Builder", false, loader)
                type.getMethod("createSyncCaptureListener")
                return root
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            }
        }
        throw ClassNotFoundException("No complete ScreenCaptureInternal/ScreenCapture legacy family")
    }
}
