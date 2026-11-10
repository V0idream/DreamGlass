package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NotificationArrivalCaptureHook
import org.junit.Assert.*
import org.junit.Test

class NotificationArrivalCaptureHookTest {
    class CurrentRow {
        fun setHeadsUp(value: Boolean) { value.hashCode() }
        fun setEntryAdapter(value: Any?) { value?.hashCode() }
        fun unrelated() = Unit
    }
    class LegacyRow {
        fun setEntry(value: Any?) { value?.hashCode() }
        fun onNotificationUpdated() = Unit
    }
    abstract class UnsupportedRow {
        abstract fun onNotificationUpdated()
        fun setHeadsUp(value: Int) { value.hashCode() }
        fun setEntry(value: Boolean) { value.hashCode() }
        fun setEntryAdapter(value: Any?): Int = value.hashCode()
        companion object { @JvmStatic fun setHeadsUp(value: Boolean) { value.hashCode() } }
    }

    @Test fun supportsCurrentCos17AndLegacyNativeEventsWithoutRequiringEveryMethod() {
        assertEquals(setOf("setHeadsUp", "setEntryAdapter"),
            NotificationArrivalCaptureHook.supportedMethods(CurrentRow::class.java).map { it.name }.toSet())
        assertEquals(setOf("setEntry", "onNotificationUpdated"),
            NotificationArrivalCaptureHook.supportedMethods(LegacyRow::class.java).map { it.name }.toSet())
    }

    @Test fun rejectsStaticAbstractOrChangedNativeSignatures() {
        assertTrue(NotificationArrivalCaptureHook.supportedMethods(UnsupportedRow::class.java).isEmpty())
    }
}
