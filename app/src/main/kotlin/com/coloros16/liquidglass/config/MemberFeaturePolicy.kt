/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.config

import android.content.SharedPreferences

/** Persistent honor confirmation, not payment verification or DRM.
 * Confirmation must come from the explicit membership action, never a config
 * import. Hook processes must consume [wrap], not the unfiltered backing store.
 */
object MemberFeaturePolicy {
    const val KEY_CONFIRMED_VERSION = "dreamglass_member_confirmed_version"
    internal val confirmationKeys = setOf(KEY_CONFIRMED_VERSION, "honor_confirmed")

    // Explicit allow-list: ordinary QS/notification rendering and Launcher glass
    // (card_glass_enable, clear_all_btn_glass_enable) remain free. Do not gate all
    // '*glass*' or '*anim*' keys by prefix: that would also disable native tuning.
    val memberBooleanKeys: Set<String> = setOf(
        "notification_dissolve_enabled", "recents_dissolve_enabled",
        "lockscreen_clock_glass_enable", "keyguard_glass_enable", "keyguard_glass_text",
        "keyguard_glass_cards", "keyguard_glass_shortcuts",
        "gallery_glass_enable", "smart_sidebar_glass_enable",
        "assistant_screen_glass_enable", "gallery_shader_probe_enable",
        "notification_popup_glass_enable",
        "seedling_card_glass_enable", "seedling_capsule_glass", "seedling_card_force_transparent_bg",
        "anim_tilt_enabled", "notif_tilt_enable"
    )

    fun versionToken(name: String, code: Int): String = "$code@$name"
    // Kept under the existing key for upgrades. Confirmation is no longer tied
    // to an APK version; historical explicit tokens remain valid.
    const val currentVersion: String = "honor-confirmed-v1"
    private val historicalToken=Regex("[1-9][0-9]{0,9}@[0-9][A-Za-z0-9.+_-]{0,63}")
    internal fun tokenConfirmed(token:String?,version:String=currentVersion):Boolean =
        token==currentVersion || token==version || token!=null && historicalToken.matches(token) &&
            token.substringBefore('@').toLongOrNull()?.let {it in 1..Int.MAX_VALUE.toLong()}==true

    fun wrap(raw: SharedPreferences): SharedPreferences = wrap(raw, currentVersion)
    internal fun wrap(raw: SharedPreferences, version: String): SharedPreferences =
        if (raw is GatedPreferences && raw.version == version) raw
        else GatedPreferences(unfiltered(raw), version)

    /** No in-memory entitlement cache: remote-store changes/revocation are
     * observed on the next preference read. The backing service may still have
     * its own propagation delay, which this layer cannot eliminate. */
    fun confirmed(raw: SharedPreferences): Boolean = confirmed(raw, currentVersion)
    fun isConfirmed(raw: SharedPreferences): Boolean = confirmed(raw)
    internal fun confirmed(raw: SharedPreferences, version: String): Boolean = try {
        tokenConfirmed(unfiltered(raw).getString(KEY_CONFIRMED_VERSION, null),version)
    } catch (_: RuntimeException) { false }

    /** Call only from the explicit 'I have paid, continue' action, on BOTH the
     * module-local and framework backing stores. No feature is enabled here. */
    fun confirmExplicitly(raw: SharedPreferences): Boolean = confirmExplicitly(raw, currentVersion)
    fun confirm(raw: SharedPreferences): Boolean = confirmExplicitly(raw)
    internal fun confirmExplicitly(raw: SharedPreferences, version: String): Boolean =
        unfiltered(raw).edit().putString(KEY_CONFIRMED_VERSION, version).commit()

    /** Closes every member switch without changing any free feature or slider. */
    fun clearMemberSwitches(raw: SharedPreferences): Boolean {
        val editor = unfiltered(raw).edit()
        memberBooleanKeys.forEach { editor.putBoolean(it, false) }
        return editor.commit()
    }

    /** 'Not paying, exit' / reset action. Atomic per backing store. */
    fun revokeAndDisable(raw: SharedPreferences): Boolean {
        val editor = unfiltered(raw).edit()
        confirmationKeys.forEach { editor.remove(it) }
        memberBooleanKeys.forEach { editor.putBoolean(it, false) }
        return editor.commit()
    }
    fun lock(raw: SharedPreferences): Boolean = revokeAndDisable(raw)

    /** Deny only never-confirmed/revoked stores. An APK update must not clear
     * a prior explicit confirmation or the user's selected member switches. */
    fun disableIfUnconfirmed(raw: SharedPreferences): Boolean =
        if (confirmed(raw)) true else revokeAndDisable(raw)

    internal fun unfiltered(raw: SharedPreferences): SharedPreferences =
        if (raw is GatedPreferences) unfiltered(raw.source) else raw
}
