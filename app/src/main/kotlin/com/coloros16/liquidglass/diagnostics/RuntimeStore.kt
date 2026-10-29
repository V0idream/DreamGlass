package com.coloros16.liquidglass.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.coloros16.liquidglass.log.Lg

/** Writable only in the module process, exposed to authenticated hosts through LogProvider. */
object RuntimeStore {
    val HOSTS=setOf("com.android.systemui","com.android.launcher","com.coloros.gallery3d",
        "com.coloros.calculator","com.coloros.smartsidebar","com.coloros.assistantscreen")
    val URI: Uri = Uri.parse("content://com.dreamglass.cos17.lgfile")
    const val STATE = "runtime-state"
    const val LOG = "runtime-log"
    const val CLASSES = "runtime-classes"
    fun local(context: Context): SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("dreamglass_runtime", Context.MODE_PRIVATE)

    fun sendState(host: String, snapshot: String): Boolean {
        val context = Lg.processContext() ?: return false
        return context.contentResolver.call(URI, STATE, host, Bundle().apply { putString("snapshot", snapshot) })
            ?.getBoolean("accepted", false) == true
    }

    fun sendLog(slug: String, heartbeat: String, tail: String) {
        val context = Lg.processContext() ?: return
        context.contentResolver.call(URI, LOG, slug, Bundle().apply {
            putString("heartbeat", heartbeat.take(512)); putString("tail", tail.takeLast(4000))
        })
    }

    fun sendClasses(host: String, classes: String): Boolean {
        val context = Lg.processContext() ?: return false
        return context.contentResolver.call(URI, CLASSES, host, Bundle().apply { putString("classes", classes) })
            ?.getBoolean("accepted", false) == true
    }
}
