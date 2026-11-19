package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import com.coloros16.liquidglass.glass.SharedGlassParams
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object LauncherFolderGlassSurfaceHook {
    private val states = WeakHashMap<ImageView, State>()
    private val wallpaperReady: () -> Unit = { states.keys.toList().forEach(View::invalidate) }
    private var config: SharedGlassParams.Snapshot? = null
    private var readAt = -1000L

    fun install(module: XposedInterface, loader: ClassLoader) {
        runCatching {
            val background = Class.forName("com.android.launcher3.folder.FolderRoundImageView", false, loader)
            val folder = Class.forName("com.android.launcher3.folder.FolderIcon", false, loader)
            val previewType = Class.forName("com.android.launcher3.folder.OplusPreviewBackground", false, loader)
            check(ImageView::class.java.isAssignableFrom(background) && View::class.java.isAssignableFrom(folder))
            val preview = folder.getDeclaredField("mBackground").apply { check(type == previewType); isAccessible = true }
            val backgroundView = previewType.getDeclaredField("mBgView").apply { check(type == background); isAccessible = true }
            val isBig = runCatching { folder.getMethod("isBigFolderIcon") }.getOrElse {
                Class.forName("com.android.launcher3.folder.FlexibleFolderIcon", false, loader).getMethod("isBigFolderIcon")
            }
            val radius = background.getMethod("getMRadius")
            check(isBig.returnType == Boolean::class.javaPrimitiveType && radius.returnType == Float::class.javaPrimitiveType)
            fun parentFolder(view: View): View? {
                var parent = view.parent as? View
                repeat(5) {
                    val node = parent ?: return null
                    if (folder.isInstance(node)) return node
                    parent = node.parent as? View
                }
                return null
            }
            val drawCover = background.getDeclaredField("mDrawCoverDrawable").apply { isAccessible = true }
            val draw = background.getDeclaredMethod("onDraw", Canvas::class.java)
            module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val view = chain.getThisObject() as? ImageView
                val canvas = chain.getArg(0) as? Canvas
                val drawn = if (view == null || canvas?.isHardwareAccelerated != true ||
                    Looper.myLooper() != Looper.getMainLooper()) false else runCatching {
                    val now = SystemClock.uptimeMillis()
                    if (now - readAt >= 500L) {
                        readAt = now
                        val prefs = Prefs.read(module)
                        config = if (prefs.getBoolean(Prefs.KEY_MASTER, false) &&
                            prefs.getBoolean(Prefs.KEY_GLASS_ENABLE, Prefs.DEFAULT_GLASS_ENABLE))
                            SharedGlassParams.read(prefs) else null
                    }
                    val current = config ?: return@runCatching false
                    val owner = parentFolder(view) ?: return@runCatching false
                    if (!view.isAttachedToWindow || drawCover.getBoolean(view)) return@runCatching false
                    val ownerPreview = preview.get(owner) ?: return@runCatching false
                    if (backgroundView.get(ownerPreview) !== view || !isBig.declaringClass.isInstance(owner) ||
                        isBig.invoke(owner) != true) return@runCatching false
                    val state = states.getOrPut(view) { State(view) }
                    state.bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    state.material.draw(view, canvas, state.bounds, radius.invoke(view) as Float, current, wallpaperReady)
                }.onFailure {
                    RuntimeDiagnostics.update("large_folder_glass_fallback", it.javaClass.simpleName)
                }.getOrDefault(false)
                if (drawn) null else chain.proceed()
            }
            runCatching { module.deoptimize(draw) }
            RuntimeDiagnostics.mark("large_folder_glass_contract", "大文件夹使用已就绪的桌面壁纸；缺失时保留系统底板")
        }.onFailure {
            RuntimeDiagnostics.update("large_folder_glass_fallback", "保留系统底板：${it.javaClass.simpleName}")
        }
    }

    private class State(view: ImageView) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val host = WeakReference(view)
        val material by lazy { LauncherWallpaperSurface() }
        val bounds = RectF()
        private val observer = view.viewTreeObserver
        private val position = IntArray(2)
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        init {
            observer.addOnPreDrawListener(this)
            view.addOnAttachStateChangeListener(this)
        }
        override fun onPreDraw(): Boolean {
            val view = host.get() ?: return true
            if (view.isShown) {
                view.getLocationOnScreen(position)
                if (position[0] != lastX || position[1] != lastY) {
                    lastX = position[0]
                    lastY = position[1]
                    view.invalidate()
                }
            }
            return true
        }
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            view.removeOnAttachStateChangeListener(this)
            states.remove(view)
        }
    }
}
