package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Looper
import android.widget.ImageView
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Quick-card appicon images have an independent getter/cache, not ImageView.mDrawable.
 * Keep engine originals intact; redirect only verified appicon slots and their refreshes. */
internal object IconGlassAppIconSlot {
    private data class Slot(val uri:Uri,val component:ComponentName?,var replacement:Drawable?=null)
    private val slots=WeakHashMap<ImageView,Slot>()
    private val readers=WeakHashMap<Class<*>,Method?>()
    private val getters=HashSet<Method>()
    private fun main()=Looper.myLooper()==Looper.getMainLooper()
    private fun uri(view:ImageView):Uri? {
        val type=view.javaClass
        if(type !in readers) {
            var current:Class<*>?=type;var found:Method?=null
            while(current!=null && current!=ImageView::class.java && found==null) {
                found=runCatching {current!!.getDeclaredMethod("getRealUri").apply {isAccessible=true}}.getOrNull()
                current=current.superclass
            }
            readers[type]=found
        }
        return runCatching {readers[type]?.invoke(view) as? Uri}.getOrNull()
    }
    fun register(view:ImageView,module:XposedInterface):Boolean {
        val source=uri(view) ?: return false
        if(source.scheme!="appicon" || source.authority!="app")return false
        val pkg=source.pathSegments.singleOrNull()?.takeIf {it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))} ?: return false
        if(slots[view]?.uri!=source) {
            val component=runCatching {view.context.packageManager.getLaunchIntentForPackage(pkg)?.component}.getOrNull()
            slots[view]=Slot(source,component)
        }
        val getter=view.javaClass.getMethod("getDrawable")
        if(getter.declaringClass!=ImageView::class.java && getters.add(getter)) {
            module.deoptimize(getter)
            module.hook(getter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val original=chain.proceed()
                val image=chain.getThisObject() as? ImageView
                if(!main() || image==null)original else replacement(image) ?: original
            }
        }
        RuntimeDiagnostics.mark("icon_suggestion_appicon","已识别快应用 appicon URI，接入引擎 getter 与刷新绑定")
        return true
    }
    fun component(view:ImageView)=slots[view]?.component
    fun registered(view:ImageView)=current(view)!=null
    private fun current(view:ImageView):Slot? {
        val slot=slots[view] ?: return null
        if(uri(view)!=slot.uri) {slots.remove(view);return null}
        return slot
    }
    fun replacement(view:ImageView):Drawable?=current(view)?.replacement?.takeIf {
        (it as? BitmapDrawable)?.bitmap?.let(LauncherIconGlassAssets::isGenerated)==true
    }
    /** Called before the native ImageView setter. Also catches engine r(cacheDrawable). */
    fun binding(view:ImageView,incoming:Drawable?):Drawable? {
        val slot=current(view) ?: return incoming
        if(incoming==null) {slot.replacement=null;return null}
        val bitmap=(incoming as? BitmapDrawable)?.bitmap
        if(bitmap!=null && LauncherIconGlassAssets.isGenerated(bitmap)) {
            slot.replacement=incoming;return incoming
        }
        return replacement(view) ?: incoming
    }
}
