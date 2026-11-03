/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Names verified against Shelf 17.14.60's layout_screen_headview.xml and
 * layout_shortcuts_card.xml. Never include the adjacent head_icon/avatar.
 */
internal object AssistantSpecificSurfacePolicy {
    private val headerIds=setOf("search_bar","ai_icon","add_icon")
    fun headerControl(exactHeaderClass:Boolean,underHeader:Boolean,id:String):Boolean =
        exactHeaderClass && underHeader && id in headerIds
    fun servicesCard(exactCardClass:Boolean,id:String):Boolean=exactCardClass && id=="shortcut_card"
}
