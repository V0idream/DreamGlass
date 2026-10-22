package com.coloros16.liquidglass.hook

/** Independent volume windows own their capture, tint, progress and reveal animation. */
object NativeScenePolicy {
    private val volumeNames = listOf(
        "com.oplus.systemui.volume.", "com.android.systemui.volume.",
        "OplusVolumeSeekBar", "OplusVolumeRow", "OplusVolumeSettingsButton",
        "OplusVolumeSideAccessoryButton", "VolumeDialog", "volume_dialog",
        "volume_panel", "volume_seekbar", "volume_progress",
    )
    private val otherNativeNames = listOf("shutdown", "globalactions", "global_actions")

    fun isVolume(identity: String): Boolean = volumeNames.any { identity.contains(it, ignoreCase = true) }

    fun requiresNative(identity: String): Boolean = isVolume(identity) ||
        otherNativeNames.any { identity.contains(it, ignoreCase = true) }
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
