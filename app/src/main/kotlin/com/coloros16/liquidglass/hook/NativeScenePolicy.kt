package com.coloros16.liquidglass.hook

/** Independent volume windows own their capture, tint, progress and reveal animation. */
object NativeScenePolicy {
    private val simpleVolumeClasses = setOf(
        "OplusVolumeSeekBar", "OplusVolumeRow", "OplusVolumeDialogView", "VolumeDialogImpl",
        "OplusVolumeSettingsButton", "OplusVolumeSideAccessoryButton",
    )

    fun isVolume(identity: String): Boolean {
        // DebugTool names can append a resource ID. Only the leading runtime class is evidence;
        // IDs such as qs_volume_seekbar or labels containing "volume" are not independent windows.
        val className = identity.trimStart().substringBefore('{').substringBefore('#').substringBefore(' ')
        return className.startsWith("com.oplus.systemui.volume.") ||
            className.startsWith("com.android.systemui.volume.") ||
            className.substringBefore('$') in simpleVolumeClasses
    }
}

/** A wrapper's nested posteffect draw must stay native even if no scene registration exists. */
object NativeDrawScope {
    private val depth = ThreadLocal<Int>()
    fun isActive(): Boolean = (depth.get() ?: 0) > 0
    fun <T> runNative(block: () -> T): T {
        val before = depth.get() ?: 0
        depth.set(before + 1)
        return try { block() } finally {
            if (before == 0) depth.remove() else depth.set(before)
        }
    }
}
