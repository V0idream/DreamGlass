/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

/** Non is also used for ordinary cards, so only admit it in the native heads-up selector
 * or while drawing an attached heads-up background. Never change other native materials. */
internal object NotificationMaterialRoute {
    fun shouldReplace(enabled: Boolean, powerSave: Boolean, notificationBackground: Boolean,
                      motion: Boolean, non: Boolean, headsUpSelection: Boolean): Boolean =
        enabled && !powerSave && notificationBackground && (motion || (non && headsUpSelection))
}
