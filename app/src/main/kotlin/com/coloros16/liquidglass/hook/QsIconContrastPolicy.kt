/* DreamGlass, 2026-10-25. Neutral QS foreground ownership and contrast policy; GPL-3.0. */
package com.coloros16.liquidglass.hook

object QsIconContrastPolicy {
    private val excludedHosts = listOf("media", "seekbar", "slider", "volumedialog",
        "notificationrow", "notificationbackground", "statusbaricon", "statusiconcontainer", "batterymeter", "privacychip")
    private val excludedImages = listOf("background", "backdrop", "artwork", "preview", "thumb", "avatar")
    fun neutral(color: Int): Boolean {
        if (color ushr 24 == 0) return false
        val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
        return maxOf(r,g,b) - minOf(r,g,b) <= 20
    }

    fun foreground(original: Int, luminance: Double?): Int? =
        if (!neutral(original) || luminance == null || !luminance.isFinite()) null
        else LocalTextContrast.readableColor(original, luminance)

    fun qsHost(name: String): Boolean = name.contains(".qs.") || name.contains(".plugins.qs.") ||
        name.substringAfterLast('.').let { it.startsWith("OplusQS") || it.startsWith("OplusQs") }

    fun excluded(name: String): Boolean {
        val simple = name.substringAfterLast('.').lowercase()
        return name.contains(".volume.") || excludedHosts.any { simple.contains(it) }
    }

    fun iconId(name: String): Boolean {
        val id = name.lowercase()
        if (id.startsWith("bg_") || id.contains("_bg") ||
            excludedImages.any { id.contains(it) }) return false
        return id.contains("icon") || id.contains("button") || id.startsWith("btn_") || id == "settings" || id == "edit"
    }
}
