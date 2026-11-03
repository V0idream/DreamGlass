package com.coloros16.liquidglass

import android.content.SharedPreferences
import com.coloros16.liquidglass.config.MemberFeaturePolicy
import org.junit.Assert.*
import org.junit.Test

class MemberFeaturePolicyTest {
    private val version = MemberFeaturePolicy.versionToken("1.1.9-debug1", 1_001_009)
    private val feature = "notification_dissolve_enabled"
    private fun wrapped(raw: MemoryPreferences) = MemberFeaturePolicy.wrap(raw, version)
    private fun confirm(raw: SharedPreferences) { assertTrue(MemberFeaturePolicy.confirmExplicitly(raw, version)) }

    @Test fun oldRemoteTrueAndDefaultTrueAreBothDeniedWithoutCurrentConfirmation() {
        val raw = MemoryPreferences()
        MemberFeaturePolicy.memberBooleanKeys.forEach { raw.edit().putBoolean(it, true).commit() }
        raw.edit().putBoolean("honor_confirmed", true).commit()
        val prefs = wrapped(raw)
        MemberFeaturePolicy.memberBooleanKeys.forEach {
            assertFalse(it, prefs.getBoolean(it, true)); assertEquals(false, prefs.all[it])
        }
        val empty = wrapped(MemoryPreferences())
        assertFalse(empty.getBoolean("keyguard_glass_text", true))
    }

    @Test fun freeNotificationQsAndLauncherGlassRemainUnmodified() {
        val raw = MemoryPreferences(); val prefs = wrapped(raw)
        for (key in listOf("master_enabled", "glass_enable", "card_glass_enable", "clear_all_btn_glass_enable",
            "volume_seekbar_glass_enable", "anim_open_enabled")) {
            prefs.edit().putBoolean(key, true).commit()
            assertTrue(key, prefs.getBoolean(key, false)); assertTrue(raw.getBoolean(key, false))
        }
        prefs.edit().putFloat("blur_radius", 17f).putString("glass_presets", "[]").commit()
        assertEquals(17f, prefs.getFloat("blur_radius", 0f), 0f)
        assertEquals("[]", prefs.getString("glass_presets", null))
    }

    @Test fun notificationMenuIsMemberButRetiredCalculatorIsNotManaged() {
        assertTrue(MemberFeaturePolicy.memberBooleanKeys.contains("notification_popup_glass_enable"))
        assertFalse(MemberFeaturePolicy.memberBooleanKeys.contains("calculator_glass_enable"))
        val raw = MemoryPreferences()
        raw.edit().putBoolean("notification_popup_glass_enable", true).commit()
        assertFalse(wrapped(raw).getBoolean("notification_popup_glass_enable", true))
    }

    @Test fun explicitConfirmationUnlocksOnlyThisExactVersionNameAndCode() {
        val raw = MemoryPreferences(); raw.edit().putBoolean(feature, true).commit(); confirm(raw)
        assertTrue(wrapped(raw).getBoolean(feature, false))
        val changedName = MemberFeaturePolicy.wrap(raw, MemberFeaturePolicy.versionToken("1.1.9", 1_001_009))
        val changedCode = MemberFeaturePolicy.wrap(raw, MemberFeaturePolicy.versionToken("1.1.9-debug1", 1_001_010))
        assertFalse(changedName.getBoolean(feature, true)); assertFalse(changedCode.getBoolean(feature, true))
    }

    @Test fun configImportCannotWriteOrExportTokenOrLegacyConfirmation() {
        val raw = MemoryPreferences(); val prefs = wrapped(raw)
        prefs.edit().putString(MemberFeaturePolicy.KEY_CONFIRMED_VERSION, version)
            .putBoolean("honor_confirmed", true).putBoolean(feature, true).commit()
        assertFalse(MemberFeaturePolicy.confirmed(raw, version)); assertFalse(raw.getBoolean(feature, true))
        confirm(raw)
        assertFalse(prefs.contains(MemberFeaturePolicy.KEY_CONFIRMED_VERSION))
        assertFalse(prefs.all.containsKey(MemberFeaturePolicy.KEY_CONFIRMED_VERSION))
        assertNull(prefs.getString(MemberFeaturePolicy.KEY_CONFIRMED_VERSION, null))
        prefs.edit().putString(MemberFeaturePolicy.KEY_CONFIRMED_VERSION, "forged").commit()
        assertTrue(MemberFeaturePolicy.confirmed(raw, version))
    }

    @Test fun deferredEditorCannotKeepTrueAfterRevocationOrGrantWhilePutWasDenied() {
        val raw = MemoryPreferences(); val prefs = wrapped(raw); confirm(raw)
        val pending = prefs.edit().putBoolean(feature, true)
        MemberFeaturePolicy.revokeAndDisable(raw)
        pending.commit()
        assertFalse(raw.getBoolean(feature, true)); assertFalse(prefs.getBoolean(feature, true))
        val denied = prefs.edit().putBoolean(feature, true)
        confirm(raw); denied.apply()
        assertFalse(raw.getBoolean(feature, true))
    }

    @Test fun clearPlusImportedTokenCannotRestoreConfirmationOrDefaultTrueMembers() {
        val raw = MemoryPreferences(); val prefs = wrapped(raw); confirm(raw)
        prefs.edit().clear().putString(MemberFeaturePolicy.KEY_CONFIRMED_VERSION, version)
            .putBoolean(feature, true).commit()
        assertFalse(MemberFeaturePolicy.confirmed(raw, version))
        MemberFeaturePolicy.memberBooleanKeys.forEach { assertEquals(false, raw.all[it]) }
    }

    @Test fun nonBooleanFeatureWritesCannotSmuggleAnEnabledStringOrInteger() {
        val raw = MemoryPreferences(); val prefs = wrapped(raw)
        prefs.edit().putString(feature, "true").putInt("anim_tilt_enabled", 1).apply()
        assertEquals(false, raw.all[feature]); assertEquals(false, raw.all["anim_tilt_enabled"])
        confirm(raw); prefs.edit().putString(feature, "true").commit()
        assertFalse(prefs.getBoolean(feature, true))
    }

    @Test fun lockAtomicallyClosesAllMembersButPreservesFreeFlagsAndParameters() {
        val raw = MemoryPreferences(); confirm(raw)
        MemberFeaturePolicy.memberBooleanKeys.forEach { raw.edit().putBoolean(it, true).commit() }
        raw.edit().putBoolean("card_glass_enable", true).putFloat("blur_radius", 21f).commit()
        assertTrue(MemberFeaturePolicy.lock(raw)); assertFalse(MemberFeaturePolicy.confirmed(raw, version))
        MemberFeaturePolicy.memberBooleanKeys.forEach { assertEquals(false, raw.all[it]) }
        assertTrue(raw.getBoolean("card_glass_enable", false)); assertEquals(21f, raw.getFloat("blur_radius", 0f), 0f)
    }

    @Test fun remoteTokenChangesNotifyFeatureListenersThroughGatedSenderAndUnregisterWorks() {
        val raw = MemoryPreferences(); val prefs = wrapped(raw); var observed = 0; var enabled = true
        raw.edit().putBoolean(feature, true).commit()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sender, key ->
            if (key == feature) { assertSame(prefs, sender); observed++; enabled = sender.getBoolean(key, true) }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        confirm(raw); assertEquals(1, observed); assertTrue(enabled)
        MemberFeaturePolicy.revokeAndDisable(raw); assertTrue(observed >= 2); assertFalse(enabled)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        val previous = observed; confirm(raw); assertEquals(previous, observed)
    }

    @Test fun wrappingTwiceDoesNotLayerAnOldVersionDenialOverCurrentConfirmation() {
        val raw = MemoryPreferences(); raw.edit().putBoolean(feature, true).commit(); confirm(raw)
        val oldWrapper = MemberFeaturePolicy.wrap(raw, "old")
        val current = MemberFeaturePolicy.wrap(oldWrapper, version)
        assertTrue(current.getBoolean(feature, false)); assertSame(current, MemberFeaturePolicy.wrap(current, version))
    }

    /** No Android runtime calls: tests the actual SharedPreferences facade against
     * a synchronous backing store, including framework-style remote changes. */
    internal class MemoryPreferences : SharedPreferences {
        val values = LinkedHashMap<String?, Any?>()
        var commitAttempts = 0
            private set
        val failCommitNumbers = mutableSetOf<Int>()
        var throwOnCommit = false
        var failStringReads = false
        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun getAll(): MutableMap<String, *> = values.filterKeys { it != null }.mapKeys { it.key!! }.toMutableMap()
        override fun contains(key: String?) = values.containsKey(key)
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun getString(key: String?, defValue: String?): String? {
            check(!failStringReads) { "Injected preference read failure" }
            return values[key] as? String ?: defValue
        }
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listener?.let(listeners::add) }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String?, Any?>(); private var clear = false
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values?.toSet(); return this }
            override fun remove(key: String?): SharedPreferences.Editor { pending[key] = null; return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean {
                commitAttempts++
                check(!throwOnCommit) { "Injected preference commit failure" }
                if (commitAttempts in failCommitNumbers) return false
                val changed = LinkedHashSet<String?>()
                if (clear) { changed.addAll(values.keys); values.clear() }
                for ((key, value) in pending) { if (value == null) values.remove(key) else values[key] = value; changed += key }
                for (key in changed) listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemoryPreferences, key) }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
