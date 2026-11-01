/* DreamGlass contributors. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** A carousel's clip is a window onto its drawable, not a smaller rounded card.
 * In particular, negative scroll bounds must not be interpreted as metaball padding.
 */
object MediaSurfaceGeometry {
    private val owners = setOf(
        "com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView",
        "com.oplus.systemui.qs.media.OplusQsNormalMediaPanelView",
        "com.oplus.systemui.qs.media.SepQsLargeMediaPanelView",
        "com.oplus.systemui.media.controls.ui.OplusMediaCarouselView",
        "com.oplus.systemui.plugins.shared.template.section.media.MediaPlayerCardPageRootView",
    )

    fun owns(name: String): Boolean = name in owners

    data class Viewport(val width: Float, val height: Float, val sourceLeft: Float, val sourceTop: Float)
    fun resolve(left: Int, top: Int, right: Int, bottom: Int): Viewport? {
        val width = right.toLong() - left
        val height = bottom.toLong() - top
        if (width !in 2..16384 || height !in 2..16384) return null
        return Viewport(width.toFloat(), height.toFloat(), left.toFloat(), top.toFloat())
    }
}
