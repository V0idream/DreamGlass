/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.RectF
import android.view.View
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.entry.XposedEntry
import io.github.libxposed.api.XposedInterface

/** Only verified surface owners are mounted. Preserve foreground rendering and
 * synchronize opaque HWUI metadata through ordinary background setter changes.
 * Each adapter owns its native fill only. */
internal object Cos17AppSurfaceHooks {
    private var targets:List<Class<*>> = emptyList()
    fun isTarget(view:View)=targets.any {it.isInstance(view)} || AssistantSurfaceAdapter.isSpecificTarget(view) || GallerySurfaceAdapter.matches(view)
    fun isNativeCard(view:View)=AssistantSurfaceAdapter.isNativeCard(view)
    fun nativeBounds(view:View):RectF? = AssistantSurfaceAdapter.nativeBounds(view) ?: GallerySurfaceAdapter.nativeBounds(view)
    fun nativeFillAlpha(view:View)=AssistantSurfaceAdapter.nativeFillAlpha(view) ?: 1f
    fun beforeAssign(view:View,active:Boolean)=GallerySurfaceAdapter.beforeAssign(view,active)
    fun nativeRadius(view:View)=GallerySurfaceAdapter.nativeRadius(view) ?: AssistantSurfaceAdapter.nativeRadius(view)
    fun nativeRole(view:View)=GallerySurfaceAdapter.nativeRole(view)
    fun onMaterialStateChanged(view:View,active:Boolean) {
        AssistantSurfaceAdapter.onMaterialStateChanged(view,active)
        GallerySurfaceAdapter.onMaterialStateChanged(view,active)
    }
    fun install(api:XposedInterface,loader:ClassLoader,pkg:String) {
        targets=try {when(pkg) {
            XposedEntry.PACKAGE_GALLERY -> GallerySurfaceAdapter.install(api,loader,AppGlassHook::materialApplied)
            XposedEntry.PACKAGE_ASSISTANT_SCREEN -> AssistantSurfaceAdapter.install(api,loader,AppGlassHook::materialApplied)
            else -> emptyList()
        }} catch(error:Throwable) {
            RuntimeDiagnostics.mark("app-glass-target-error","原生控件接口未匹配，保留原生：${error.javaClass.simpleName}")
            emptyList()
        }
        RuntimeDiagnostics.mark("app-glass-target-contract","已识别 ${targets.size} 类真实材质宿主；保留前景与原生控件布局")
    }
}
