/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.config

import android.content.SharedPreferences
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The same facade is used by UI XML, framework service and Hook API readers.
 * Keeping raw confirmation out of getAll/edit prevents ordinary config import
 * and export from granting confirmation. This is an honor gate, not a security
 * boundary against modified source, Root, or direct writes to the backing store.
 */
internal class GatedPreferences(internal val source: SharedPreferences, internal val version: String) : SharedPreferences {
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
    private fun confirmed() = MemberFeaturePolicy.confirmed(source, version)
    private fun member(key: String?) = key in MemberFeaturePolicy.memberBooleanKeys
    private fun secret(key: String?) = key in MemberFeaturePolicy.confirmationKeys
    private fun typedMemberOrSecret(key: String?) = member(key) || secret(key)

    override fun getAll(): MutableMap<String, *> {
        val values = LinkedHashMap<String, Any?>()
        for ((key, value) in source.all) {
            if (!secret(key)) values[key] = if (value is Set<*>) value.toSet() else value
        }
        if (!confirmed()) MemberFeaturePolicy.memberBooleanKeys.forEach { values[it] = false }
        return values
    }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (secret(key) || (member(key) && !confirmed())) return false
        return source.getBoolean(key, defValue)
    }
    override fun getString(key: String?, defValue: String?): String? =
        if (typedMemberOrSecret(key)) defValue else source.getString(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (typedMemberOrSecret(key)) defValues?.toMutableSet() else source.getStringSet(key, defValues)?.toMutableSet()
    override fun getInt(key: String?, defValue: Int): Int = if (typedMemberOrSecret(key)) defValue else source.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = if (typedMemberOrSecret(key)) defValue else source.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = if (typedMemberOrSecret(key)) defValue else source.getFloat(key, defValue)
    override fun contains(key: String?): Boolean = !secret(key) && (member(key) || source.contains(key))
    override fun edit(): SharedPreferences.Editor = GatedEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener == null) return
        synchronized(listeners) {
            if (listeners.containsKey(listener)) return
            val target = WeakReference(listener)
            val owner = WeakReference(this)
            val adapter = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                val facade = owner.get()
                val callback = target.get()
                if (facade != null && callback != null) {
                    if (key == null || facade.secret(key)) {
                        // Existing listeners often watch only their feature key.
                        // A token change must invalidate those cached decisions.
                        MemberFeaturePolicy.memberBooleanKeys.forEach { callback.onSharedPreferenceChanged(facade, it) }
                        if (key == null) callback.onSharedPreferenceChanged(facade, null)
                    } else callback.onSharedPreferenceChanged(facade, key)
                }
            }
            listeners[listener] = adapter
            source.registerOnSharedPreferenceChangeListener(adapter)
        }
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener == null) return
        synchronized(listeners) { listeners.remove(listener)?.let(source::unregisterOnSharedPreferenceChangeListener) }
    }

    /** Buffer writes so revocation after putBoolean but before commit/apply
     * cannot persist a delayed 'true'. An import cannot smuggle a token alongside
     * feature keys, regardless of operation ordering or value type. */
    private inner class GatedEditor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String?, Any?>()
        private val removed = Any()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (!secret(key)) changes[key] = if (member(key)) value is Boolean && value && confirmed() else value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun putString(key: String?, value: String?) = if (value == null) remove(key) else put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = if (values == null) remove(key) else put(key, values.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor { changes[key] = removed; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        private fun materialize(): SharedPreferences.Editor {
            val editor = source.edit()
            if (clear) editor.clear()
            val allowed = !clear && confirmed() && changes.none { secret(it.key) && it.value === removed }
            for ((key, value) in changes) {
                if (value === removed) { editor.remove(key); continue }
                if (secret(key)) continue
                if (member(key)) { editor.putBoolean(key, allowed && value == true); continue }
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, (value as Set<String>).toMutableSet())
                    }
                }
            }
            // clear() revokes confirmation and must leave all member keys false,
            // including keys that otherwise default true (lockscreen children).
            if (clear) MemberFeaturePolicy.memberBooleanKeys.forEach { editor.putBoolean(it, false) }
            return editor
        }
        override fun commit(): Boolean = materialize().commit()
        override fun apply() { materialize().apply() }
    }
}
