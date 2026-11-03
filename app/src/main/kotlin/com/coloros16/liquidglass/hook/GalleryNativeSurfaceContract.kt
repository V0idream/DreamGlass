/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Gallery 17.10.41 resource/class contracts. These are the native material
 * surfaces, not their full-width toolbars, tab strip wrappers or photo content.
 * Runtime-only toolbar registration is supplied by the OEM's semantic factories.
 */
internal object GalleryNativeSurfaceContract {
    private const val BASE="com.coui.appcompat.bottomfloatingnavigationbar.BaseBottomFloatingMenuView"
    private const val MENU="com.coui.appcompat.bottomfloatingnavigationbar.COUIBottomFloatingNavigationBarMenuView"
    private const val ACCESSORY="com.coui.appcompat.bottomfloatingaccessorynavigationbar.COUIBottomFloatingAccessoryView"
    private const val PILL="com.oplus.gallery.photoeditor.editingvvm.menu.widget.MainMenuPillContainerLayout"
    private const val DIALOG="com.coui.appcompat.dialog.widget.COUIAlertDialogMaxLinearLayout"
    private const val BUTTON="com.coui.appcompat.button.COUIButton"
    private data class Capsule(val type:Class<*>,val radius:Field,val bounds:Field?=null)
    private var photoMenu:Capsule?=null
    private var photoMenuBounds:Method?=null
    private var photoMenuSync:Method?=null
    private val photoButtonHosts=WeakHashMap<View,Boolean>()
    private var menu:Capsule?=null
    private var accessory:Capsule?=null
    private var pill:Capsule?=null
    private var dialog:Capsule?=null
    private var button:Class<*>?=null
    private var buttonRadius:Method?=null
    private var buttonBounds:Field?=null
    private val toolbarHosts=WeakHashMap<View,Boolean>()
    private enum class Kind { MENU,ACCESSORY,PILL,DIALOG,BUTTON,TOOLBAR,PHOTO_MENU,PHOTO_BUTTON }
    private fun id(view:View)=runCatching {view.resources.getResourceEntryName(view.id)}.getOrDefault("")
    private fun dialogAncestor(view:View):View? {
        var parent=view.parent
        repeat(12) {
            val v=parent as? View ?: return null
            if(dialog?.type?.isInstance(v)==true && id(v)=="parentPanel")return v
            parent=v.parent
        }
        return null
    }
    private fun kind(view:View):Kind? = when {
        photoButtonHosts[view]==true -> Kind.PHOTO_BUTTON
        photoMenu?.type?.isInstance(view)==true -> Kind.PHOTO_MENU
        toolbarHosts[view]==true && view !is ViewGroup -> Kind.TOOLBAR
        menu?.type?.isInstance(view)==true -> Kind.MENU
        accessory?.type?.isInstance(view)==true -> Kind.ACCESSORY
        pill?.type?.isInstance(view)==true && id(view)=="main_menu_pill_container" -> Kind.PILL
        dialog?.type?.isInstance(view)==true && id(view)=="parentPanel" -> Kind.DIALOG
        button?.isInstance(view)==true &&
            (view.id in setOf(android.R.id.button1,android.R.id.button2,android.R.id.button3) ||
                id(view) in setOf("btn_save_again_cancel","btn_save_again_confirm")) && dialogAncestor(view)!=null -> Kind.BUTTON
        else -> null
    }
    fun matches(view:View)=kind(view)!=null
    fun isDialogButton(view:View)=kind(view)==Kind.BUTTON
    fun role(view:View):Int?=kind(view)?.let {
        if(it==Kind.DIALOG)AppGlassSurfacePolicy.PANEL else AppGlassSurfacePolicy.CONTROL
    }
    fun radius(view:View):Float?=runCatching {
        when(kind(view)) {
            // Native mRadius is refreshed only in onDraw/onLayout. onPreDraw
            // must use this frame's same formula, not a previous-size cache.
            Kind.PHOTO_MENU -> photoMenu!!.radius.getFloat(view)
            Kind.PHOTO_BUTTON -> minOf(view.width,view.height)/2f
            Kind.MENU -> GalleryFloatingSurfaceGeometry.radius(view.height.toFloat(),view.paddingTop,view.paddingBottom)
            Kind.ACCESSORY -> GalleryFloatingSurfaceGeometry.radius(view.measuredHeight.toFloat(),view.paddingTop,view.paddingBottom)
            Kind.PILL -> pill!!.radius.getFloat(view)
            Kind.DIALOG -> dialog!!.radius.getInt(view).toFloat()
            Kind.BUTTON -> (buttonRadius?.invoke(view) as? Number)?.toFloat()
            Kind.TOOLBAR -> view.height/2f // ToolbarMaterialEffectDelegate's own outline/radius rule.
            null -> null
        }?.takeIf {it.isFinite() && it>=0f}
    }.getOrNull()
    fun bounds(view:View):RectF?=runCatching {
        if(view.width<=0 || view.height<=0)return@runCatching null
        val kind=kind(view) ?: return@runCatching null
        val area=if(kind==Kind.PHOTO_MENU) {
            // Called under AppGlassHook's nativeMutation guard. Native recalculates
            // these bounds before painting, so inspect the same frame's geometry.
            photoMenuSync!!.invoke(view)
            RectF(photoMenuBounds!!.invoke(view) as RectF)
        } else if(kind==Kind.MENU) {
            val drawing=menu!!.bounds!!.get(view) as RectF
            val local=GalleryFloatingSurfaceGeometry.menu(view.width.toFloat(),view.height.toFloat(),
                drawing.left,drawing.top,drawing.right,drawing.bottom,view.layoutDirection==View.LAYOUT_DIRECTION_RTL)
                ?: return@runCatching null
            RectF(local.left,local.top,local.right,local.bottom)
        } else if(kind==Kind.ACCESSORY) {
            val local=GalleryFloatingSurfaceGeometry.accessory(view.width.toFloat(),view.height.toFloat())
                ?: return@runCatching null
            RectF(local.left,local.top,local.right,local.bottom)
        } else if(kind==Kind.BUTTON)RectF(buttonBounds!!.get(view) as RectF) else if(kind==Kind.DIALOG) {
            // The native InsetDrawable's outline excludes the dialog's outer
            // transparent margins. Never turn the whole dialog window into glass.
            val outline=Outline();view.background?.getOutline(outline)
            val rect=Rect()
            if(!outline.getRect(rect) || rect.isEmpty)return@runCatching null
            RectF(rect)
        } else RectF(0f,0f,view.width.toFloat(),view.height.toFloat())
        area.takeIf {!it.isEmpty && listOf(it.left,it.top,it.right,it.bottom).all(Float::isFinite)}
    }.getOrNull()

    fun install(api:XposedInterface,loader:ClassLoader,active:(View)->Boolean) {
        photoMenu=null;photoMenuBounds=null;photoMenuSync=null;photoButtonHosts.clear()
        menu=null;accessory=null;pill=null;dialog=null;button=null;buttonRadius=null;buttonBounds=null;toolbarHosts.clear()
        var mountedCount=0
        fun mounted(name:String,block:()->Unit) {
            runCatching {block();mountedCount++}.onFailure {
                RuntimeDiagnostics.mark("gallery-native-${name}","接口未匹配，保持原生：${it.javaClass.simpleName}")
            }
        }
        fun field(type:Class<*>,name:String,expected:Class<*>)=type.getDeclaredField(name).apply {
            check(this.type==expected);isAccessible=true
        }
        fun deopt(type:Class<*>,names:Set<String>) {
            type.declaredMethods.filter {it.name in names}.forEach {api.deoptimize(it)}
        }
        fun skipFill(type:Class<*>,name:String) {
            val method=type.getDeclaredMethod(name,Canvas::class.java)
            check(method.returnType==Void.TYPE)
            api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getThisObject() as? View
                if(view!=null && matches(view) && active(view) && (chain.getArg(0) as Canvas).isHardwareAccelerated)null
                else chain.proceed()
            }
            api.deoptimize(method)
        }
        mounted("navigation") {
            val base=Class.forName(BASE,false,loader);val type=Class.forName(MENU,false,loader)
            check(base.isAssignableFrom(type))
            val capsule=Capsule(type,field(base,"mRadius",Float::class.javaPrimitiveType!!),field(base,"mDrawingBounds",RectF::class.java))
            field(base,"mBackgroundBounds",RectF::class.java)
            skipFill(base,"drawPanelStroke")
            deopt(base,setOf("onDraw","onMaterialStateChange","onAttachedToWindow","updateMaterialRoundedCorners",
                "updateBlurEffect","updateStrokeEffect","setUseMaterialEffectAttr","setIsDrawableMaterialEffect"))
            deopt(type,setOf("onDraw"))
            menu=capsule
        }
        mounted("accessory") {
            val type=Class.forName(ACCESSORY,false,loader)
            accessory=Capsule(type,field(type,"mRadius",Float::class.javaPrimitiveType!!))
            deopt(type,setOf("initMaterialAppearance","onAttachedToWindow","onMaterialStateChange","updateBlurEffect","updateStrokeEffect"))
        }
        mounted("editor-pill") {
            val type=Class.forName(PILL,false,loader)
            pill=Capsule(type,field(type,"c",Float::class.javaPrimitiveType!!))
        }
        mounted("dialog") {
            val type=Class.forName(DIALOG,false,loader)
            val capsule=Capsule(type,field(type,"mRadius",Int::class.javaPrimitiveType!!))
            dialog=capsule
        }
        mounted("dialog-buttons") {
            val type=Class.forName(BUTTON,false,loader)
            val radius=type.getDeclaredMethod("getDrawableRadius")
            check(radius.returnType==Float::class.javaPrimitiveType)
            val nativeBounds=field(type,"mTmpRectF",RectF::class.java)
            skipFill(type,"drawButtonBackground");skipFill(type,"drawMaskAndStroke")
            // Preserve native shadow fields. Suppress only the verified private
            // fill routines; TextView/loading foreground always executes once.
            deopt(type,setOf("onDraw","applyBlurEffect","applyStrokeEffect","onAttachedToWindow","onMaterialStateChange"))
            val loading=Class.forName("com.coui.appcompat.button.COUILoadingButton",false,loader)
            check(type.isAssignableFrom(loading));deopt(loading,setOf("onDraw"))
            button=type;buttonRadius=radius;buttonBounds=nativeBounds
        }
        mounted("toolbar-buttons") {
            val type=Class.forName("com.coui.appcompat.toolbar.ToolbarMaterialEffectDelegate",false,loader)
            field(type,"mView",View::class.java)
            for(name in listOf("forMenuItem","forMenuOverflowButton","forToolbarButton")) {
                val method=type.getDeclaredMethod(name,View::class.java)
                check(Modifier.isStatic(method.modifiers) && method.returnType==type)
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                    val view=chain.getArg(0) as? View ?: return@intercept chain.proceed()
                    val wasRegistered=toolbarHosts.put(view,true)
                    try {chain.proceed()} catch(error:Throwable) {
                        if(wasRegistered==null)toolbarHosts.remove(view)
                        throw error
                    }
                }
                api.deoptimize(method)
            }
            deopt(type,setOf("create","init","applyBlurEffect","applyStroke","clearBlurEffect","clearStroke",
                "syncBlurAndBackground","onMaterialStateChange"))
        }
        mounted("photo-toolbar-menus") {
            val type=Class.forName("com.coui.appcompat.bottomfloatingtoolbar.COUIBottomFloatingToolbarMenuView",false,loader)
            val nativeRadius=field(type,"mRadius",Float::class.javaPrimitiveType!!)
            val getBounds=type.getDeclaredMethod("getBounds").apply {check(returnType==RectF::class.java);isAccessible=true}
            val sync=type.getDeclaredMethod("syncVisualState").apply {check(returnType==Void.TYPE);isAccessible=true}
            skipFill(type,"drawPanelStroke")
            // Keep the native foreground and touch animation. Only suppress this
            // host's material decoration while our replacement is actually active.
            val manager=type.getDeclaredField("mMaterialEffectManager").apply {isAccessible=true}
            val draw=manager.type.getDeclaredMethod("drawMaterialEffects",Canvas::class.java,
                RectF::class.java,Path::class.java,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
            check(draw.returnType==Void.TYPE)
            val scope=ScopedDrawOwner<Any>()
            val onDraw=type.getDeclaredMethod("onDraw",Canvas::class.java)
            api.hook(onDraw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getThisObject() as View
                val owner=if(active(view) && (chain.getArg(0) as Canvas).isHardwareAccelerated)manager.get(view) else null
                scope.run(owner) {chain.proceed()}
            }
            api.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                if(scope.current()!=null && scope.current()===chain.getThisObject())null else chain.proceed()
            }
            api.deoptimize(draw)
            deopt(type,setOf("onDraw","onMaterialStateChange","onAttachedToWindow","updateBlurEffect",
                "updateStrokeEffect","syncVisualState","updateAndDrawBackgroundRect"))
            photoMenu=Capsule(type,nativeRadius)
            photoMenuBounds=getBounds;photoMenuSync=sync
        }
        mounted("photo-top-menu-buttons") {
            val type=Class.forName("com.oplus.aiunit.vision.n0q",false,loader)
            val host=field(type,"a",View::class.java)
            val factory=Class.forName("com.oplus.aiunit.vision.n0q\$b",false,loader)
            val create=factory.getDeclaredMethod("a",View::class.java,Float::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            check(Modifier.isStatic(create.modifiers) && create.returnType==type)
            api.hook(create).setExceptionMode(ExceptionMode.PROTECTIVE).intercept {chain ->
                val view=chain.getArg(0) as? View ?: return@intercept chain.proceed()
                // This semantic factory receives the internal material host,
                // including FrameLayout, not the entire PhotoTopToolbar.
                val previous=photoButtonHosts.put(view,true)
                try {
                    val result=chain.proceed()
                    if(result==null || host.get(result)!==view) {
                        if(previous==null)photoButtonHosts.remove(view)
                    }
                    result
                } catch(error:Throwable) {
                    if(previous==null)photoButtonHosts.remove(view)
                    throw error
                }
            }
            api.deoptimize(create)
            deopt(type,setOf("a","c","e","onMaterialStateChange"))
            val controller=Class.forName("com.oplus.gallery.photo_page.ui.section.photoblur.PhotoMenuMaterialEffectController",false,loader)
            api.deoptimize(controller.getDeclaredMethod("d",View::class.java,
                Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
        }
        RuntimeDiagnostics.mark("gallery-native-surfaces","已匹配 $mountedCount 组精确材质宿主；按钮／导航胶囊／对话框已登记，画面仍待设备验证")
    }
}
