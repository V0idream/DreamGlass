package com.coloros16.liquidglass.island

import android.content.SharedPreferences

internal enum class IslandKind(val label: String, val priority: Int, val enabled: Boolean) {
    CALL("来电与通话", 90, true),
    TIMER("计时与闹钟", 80, true),
    CODE("验证码", 70, false),
    LIVE("实时活动", 60, true),
    MEDIA("媒体", 50, true),
    MESSAGE("消息通知", 40, false),
    STATE("系统状态", 10, true),
}

internal object IslandPrefs {
    const val PREFIX = "island_takeover_"
    const val ENABLED = PREFIX + "enabled"
    const val GLASS = PREFIX + "glass"
    const val STACK = PREFIX + "stack"
    const val LEFT = PREFIX + "left"
    const val RIGHT = PREFIX + "right"
    const val LANDSCAPE = PREFIX + "landscape"
    const val SHOW_SOURCE = PREFIX + "source"
    const val MESSAGE_SECONDS = PREFIX + "message_seconds"
    const val AUTO_COLLAPSE = PREFIX + "collapse_seconds"
    const val MEDIA_LINGER = PREFIX + "media_linger"
    const val CHARGE = PREFIX + "charge"
    const val HEADPHONES = PREFIX + "headphones"
    const val RECORDING = PREFIX + "recording"
    const val CAMERA_X = PREFIX + "camera_x"
    const val CAMERA_Y = PREFIX + "camera_y"
    const val CAMERA_SIZE = PREFIX + "camera_size"
    const val FILTER = PREFIX + "message_apps"
    const val EXEMPT = PREFIX + "exempt_apps"
    const val ANIMATION = PREFIX + "animation_ms"
    const val OPACITY = PREFIX + "opacity"
    const val CORNER = PREFIX + "corner"
    const val WIDTH = PREFIX + "width"
    const val DEFAULT_WIDTH = 144
    const val DEFAULT_AUTO_COLLAPSE = 5
    const val MIN_AUTO_COLLAPSE = 1
    const val MAX_AUTO_COLLAPSE = 30
    const val STATUS = "fluid-island"
    const val MATERIAL_STATUS = "fluid-island-material"

    fun key(kind: IslandKind) = PREFIX + "show_" + kind.name.lowercase()
    fun show(prefs: SharedPreferences, kind: IslandKind) = prefs.getBoolean(key(kind), kind.enabled)
    fun packages(value: String?) = value.orEmpty().split(',', '\n', ' ').filter { it.isNotBlank() }.toSet()

    fun autoCollapseSeconds(stored: Int): Int =
        if (stored < MIN_AUTO_COLLAPSE) DEFAULT_AUTO_COLLAPSE else stored.coerceAtMost(MAX_AUTO_COLLAPSE)
}

internal interface ScheduledIslandActivity {
    val id: String
    val kind: IslandKind
    val changedAt: Long
    val expiresAt: Long
    val urgent: Boolean
}

internal object IslandPolicy {
    data class CapsuleVertical(val top: Int, val height: Int)

    fun capsuleVertical(cameraWidth: Int, cameraTop: Int, cameraBottom: Int,
                        margin: Int, fallbackTop: Int, fallbackHeight: Int): CapsuleVertical {
        if (cameraWidth <= 0 || cameraBottom <= cameraTop) return CapsuleVertical(fallbackTop, fallbackHeight)
        // A centered circular hole can be reported as a rectangle extending up to the
        // display edge. Its short side is the lens diameter, not the whole inset.
        val diameter = minOf(cameraWidth, cameraBottom - cameraTop)
        val top = (cameraBottom - diameter - margin).coerceAtLeast(0)
        return CapsuleVertical(top, cameraBottom - top + margin)
    }

    private val verification = Regex(
        """(?i)(?:验证码|校验码|动态密码|一次性密码|verification\s+code|security\s+code|one[- ]time(?:\s+(?:password|code))?|\bOTP\b)\s*(?:is\b|为)?\s*[:：是【\[]?\s*(?<!\d)(\d{4,8})(?!\d)|(?<!\d)(\d{4,8})(?!\d)\s*(?:是|为)\s*(?:您的|你的)?\s*(?:验证码|动态密码)"""
    )

    fun code(text: String): String? = verification.find(text)?.let {
        it.groupValues[1].ifEmpty { it.groupValues[2] }
    }

    fun suppressNative(enabled: Boolean, unlocked: Boolean, hostAttached: Boolean): Boolean =
        enabled && unlocked && hostAttached

    fun isPlaybackApp(kind: IslandKind, source: String, foreground: String?): Boolean =
        kind == IslandKind.MEDIA && source.isNotEmpty() && source == foreground

    fun <T : ScheduledIslandActivity> order(
        activities: Collection<T>, hidden: Set<String>, now: Long, selected: String?,
    ): List<T> = activities.asSequence()
        .filter { it.expiresAt > now && (it.urgent || it.id !in hidden) }
        .sortedWith(compareByDescending<T> { it.urgent }
            .thenByDescending { it.id == selected }
            .thenByDescending { it.kind.priority }
            .thenByDescending { it.changedAt }.thenBy { it.id })
        .toList()
}
