package com.coloros16.liquidglass.island

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference

/**
 * Hide the OEM presentation while taking over the unlocked screen. Keep its event feed alive
 * for the replacement island and leave the system's persistent settings alone.
 */
internal object FluidIslandHook {
    private const val CAPSULE = "com.oplus.systemui.statusbar.seeding.CapsulePluginContainer"
    private const val CONTROLLER = CAPSULE + "Controller"
    private const val HOST = "com.oplus.systemui.statusbar.seeding.SeedlingPluginManager\$SeedlingHost"
    private const val ROW = "com.android.systemui.plugins.custom.CustomRowEntry"
    private const val LISTENER = "com.android.systemui.statusbar.NotificationListener"
    private const val SHADE = "com.android.systemui.shade.ShadeExpansionStateManager"
    private const val KEYGUARD = "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
    private const val PLUGIN_CAPSULE = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainerRoot"
    private const val PLUGIN_CARD = "com.oplus.systemui.plugins.seedling.card.ui.view.CardContainer"
    private const val PLUGIN_INSTANT_CARD = "com.oplus.systemui.plugins.seedling.card.ui.view.InstantContainer"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var runtime: IslandRuntime? = null
    private class Lease(var visibility: Int, val accessibility: Int, val guard: ViewTreeObserver.OnPreDrawListener)
    private val suppressed = LinkedHashMap<View, Lease>()
    private val pluginViews = java.util.WeakHashMap<View, Unit>()
    private var listener: WeakReference<NotificationListenerService>? = null
    @Volatile private var host: WeakReference<ViewGroup>? = null
    private var native = emptyList<NativeRow>()
    private var shade = false
    private var keyguard = false
    private var prefs: SharedPreferences? = null
    private var watch: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var fake: WeakReference<ViewGroup>? = null
    private var installed = false
    @Volatile private var accepting = false

    data class NativeRow(val key: String, val packageName: String, val userId: Int,
        val notification: StatusBarNotification?, val texts: List<String>)

    fun install(api: XposedInterface, loader: ClassLoader) {
        if (installed || android.os.Build.VERSION.SDK_INT < 33) return
        installed = true
        runCatching {
            val capsuleClass = Class.forName(CAPSULE, false, loader)
            val controller = Class.forName(CONTROLLER, false, loader)
            val notificationClass = Class.forName(LISTENER, false, loader)
            val shadeClass = Class.forName(SHADE, false, loader)
            val keyguardClass = Class.forName(KEYGUARD, false, loader)
            val nativeCapsule = controller.getDeclaredField("capsulePluginContainer").apply { isAccessible = true }
            val fakeCapsule = controller.getDeclaredField("capsuleFakeContainer").apply { isAccessible = true }
            val onAttach = capsuleClass.getDeclaredMethod("onAttachedToWindow")
            val notifyRect = controller.declaredMethods.single {
                it.name == "notifyRect" && it.parameterCount == 1
            }
            val shadeChange = shadeClass.declaredMethods.single {
                it.name == "onPanelExpansionChanged" && it.parameterCount == 5 &&
                    it.parameterTypes.first() == Float::class.javaPrimitiveType
            }
            val keyguardChange = keyguardClass.getDeclaredMethod("notifyKeyguardState",
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val posted = notificationClass.getDeclaredMethod("onNotificationPosted", StatusBarNotification::class.java,
                NotificationListenerService.RankingMap::class.java)
            val removed = notificationClass.getDeclaredMethod("onNotificationRemoved", StatusBarNotification::class.java,
                NotificationListenerService.RankingMap::class.java, Int::class.javaPrimitiveType)
            val connected = notificationClass.getDeclaredMethod("onListenerConnected")
            val store = Prefs.read(api)
            prefs = store
            fun observeController(target: Any?) {
                val view = nativeCapsule.get(target) as? ViewGroup ?: return
                if (!view.isAttachedToWindow || view.display?.displayId != android.view.Display.DEFAULT_DISPLAY) return
                val proxy = fakeCapsule.get(target) as? ViewGroup
                onMain {
                    fake = proxy?.let(::WeakReference)
                    bind(view)
                    runtime?.nativeChanged()
                }
            }
            api.hook(onAttach).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                val view = chain.getThisObject() as? ViewGroup
                if (view != null) onMain { bind(view) }
                result
            }
            api.hook(notifyRect).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                runCatching { observeController(chain.getThisObject()) }
                result
            }
            for (name in listOf("onViewAttached", "onPluginAdded", "onPluginRemove", "onViewDetached")) {
                val method = controller.declaredMethods.single { it.name == name }
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { observeController(chain.getThisObject()) }
                    result
                }
            }
            val setFakeVisible = controller.getDeclaredMethod("setCapsuleFakeContainerVisibility",
                Boolean::class.javaPrimitiveType)
            api.hook(setFakeVisible).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val proxy = fakeCapsule.get(chain.getThisObject()) as? View
                    val requested = if (chain.getArg(0) == true) View.VISIBLE else View.INVISIBLE
                    if (proxy != null) onMain {
                        suppressed[proxy]?.visibility = requested
                        ensureHidden(proxy)
                    }
                }
                result
            }
            api.hook(View::class.java.getDeclaredMethod("onAttachedToWindow"))
                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.getThisObject() as? View
                    if (accepting && view != null && isPluginPresentation(view)) {
                        onMain { trackPresentation(view); runtime?.nativeChanged() }
                    }
                    result
                }
            runCatching { mountNativeRows(api, loader) }
            api.hook(shadeChange).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                val open = (chain.getArg(0) as? Float ?: 0f) > .01f || chain.getArg(1) == true
                onMain { if (shade != open) { shade = open; runtime?.sceneChanged(open) } }
                result
            }
            api.hook(keyguardChange).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val showing = chain.getArg(0) == true
                onMain {
                    keyguard = showing
                    runtime?.sceneChanged(showing)
                }
                chain.proceed()
            }
            for (method in listOf(connected, posted, removed)) {
                api.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val result = chain.proceed()
                    val source = chain.getThisObject() as? NotificationListenerService
                    val sbn = if (method != connected) chain.getArg(0) as? StatusBarNotification else null
                    onMain {
                        if (source != null && listener?.get() !== source) {
                            listener = WeakReference(source)
                            runtime?.refreshNotifications(source)
                        }
                        if (sbn != null) runtime?.onNotification(sbn, method == removed)
                    }
                    result
                }
            }
            val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key.startsWith(IslandPrefs.PREFIX) || key == Prefs.KEY_MASTER) onMain { reconcile() }
            }
            watch = observer
            store.registerOnSharedPreferenceChangeListener(observer)
            accepting = true
            onMain {
                findAttachedCapsule(capsuleClass)?.let(::bind)
                reconcile()
            }
        }.onFailure {
            accepting = false
            RuntimeDiagnostics.update(IslandPrefs.STATUS, "当前 SystemUI 未匹配接管接口，保留原流体云")
        }
    }

    fun shutdown() = onMain {
        accepting = false
        watch?.let { prefs?.unregisterOnSharedPreferenceChangeListener(it) }
        runtime?.stop()
        runtime = null
        release()
    }

    fun rows() = native
    fun refreshActiveNotifications() = runtime?.refreshNotifications(listener?.get())
    fun inShade() = shade
    fun inKeyguard() = keyguard

    fun nativeStatusBarRoot(): View? = host?.get()?.rootView?.takeIf { root ->
        root.isAttachedToWindow &&
            (root.javaClass.name == "com.android.systemui.statusbar.window.StatusBarWindowView" ||
                (root.layoutParams as? android.view.WindowManager.LayoutParams)?.type == 2000)
    }

    fun ownNative(): Boolean {
        val primary = host?.get()?.takeIf { it.isAttachedToWindow } ?: return false
        val next = listOfNotNull(primary, fake?.get()?.takeIf { it.isAttachedToWindow }) +
            pluginViews.keys.filter { it.isAttachedToWindow }
        (suppressed.keys - next.toSet()).forEach(::restore)
        next.forEach(::hide)
        return true
    }

    fun suppresses(view: View) = view in suppressed

    private fun hide(view: View) {
        if (view !in suppressed) {
            val guard = ViewTreeObserver.OnPreDrawListener { ensureHidden(view); true }
            suppressed[view] = Lease(view.visibility, view.importantForAccessibility, guard)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.addOnPreDrawListener(guard)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        ensureHidden(view)
    }

    private fun ensureHidden(view: View) {
        val lease = suppressed[view] ?: return
        if (view.visibility != View.INVISIBLE) {
            lease.visibility = view.visibility
            if (view.visibility == View.VISIBLE) {
                view.visibility = View.INVISIBLE
            }
        }
    }

    private fun restore(view: View) {
        val lease = suppressed.remove(view) ?: return
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(lease.guard)
        if (view.visibility == View.INVISIBLE && lease.visibility != View.INVISIBLE) {
            view.visibility = lease.visibility
        }
        if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            view.importantForAccessibility = lease.accessibility
    }

    fun release() = suppressed.keys.toList().forEach(::restore)

    private fun isPluginPresentation(view: View) = when (view.javaClass.name) {
        PLUGIN_CAPSULE, PLUGIN_CARD, PLUGIN_INSTANT_CARD -> true
        else -> false
    }

    private fun trackPresentation(view: View) {
        if (view.display?.displayId != android.view.Display.DEFAULT_DISPLAY || pluginViews.put(view, Unit) != null) return
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                pluginViews.remove(v)
                restore(v)
            }
        })
    }

    private fun bind(view: ViewGroup) {
        if (view.display?.displayId != android.view.Display.DEFAULT_DISPLAY || !view.isAttachedToWindow ||
            host?.get() === view) return
        runtime?.stop()
        runtime = null
        host = WeakReference(view)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (host?.get() !== v) return
                runtime?.stop()
                runtime = null
                host = null
                native = emptyList()
                release()
                reconcile()
            }
        })
        reconcile()
    }

    private fun reconcile() {
        if (!accepting) return
        val store = prefs ?: return
        val enabled = store.getBoolean(IslandPrefs.ENABLED, false) &&
            store.getBoolean(Prefs.KEY_MASTER, false)
        if (!enabled) {
            runtime?.stop()
            runtime = null
            native = emptyList()
            release()
            RuntimeDiagnostics.update(IslandPrefs.STATUS, "接管关闭，使用原流体云")
            return
        }
        val view = host?.get()
        if (view == null) {
            RuntimeDiagnostics.update(IslandPrefs.STATUS, "等待 ColorOS 原生流体云就绪")
            return
        }
        if (runtime == null) {
            attachedViews(::isPluginPresentation).forEach(::trackPresentation)
            val created = runCatching { IslandRuntime(view, store) }.getOrElse {
                RuntimeDiagnostics.update(IslandPrefs.STATUS, "接管初始化失败，使用原流体云")
                return
            }
            runtime = created
            if (runCatching { created.start(listener?.get()) }.isFailure) {
                created.stop()
                runtime = null
                RuntimeDiagnostics.update(IslandPrefs.STATUS, "接管初始化失败，使用原流体云")
            }
        } else runtime?.preferencesChanged()
    }

    private fun mountNativeRows(api: XposedInterface, loader: ClassLoader) {
        val seedlingHost = Class.forName(HOST, false, loader)
        val rowClass = Class.forName(ROW, false, loader)
        val rowKey = rowClass.getMethod("getKey")
        val rowPackage = rowClass.getMethod("getPackageName")
        val rowUser = rowClass.getMethod("getUserId")
        val rowView = rowClass.getMethod("getCustomView")
        val manager = seedlingHost.getDeclaredField("this\$0").apply { isAccessible = true }
        val interactor = manager.type.getDeclaredField("notificationLiveAlertInteractor").apply { isAccessible = true }
        val liveNotification = interactor.type.getMethod("getLiveAlertNotification", String::class.java)
        val rowChange = seedlingHost.getDeclaredMethod("onSeedingNotificationDataChange",
            List::class.java, Boolean::class.javaPrimitiveType)
        api.hook(rowChange).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            if (accepting && runtime != null) {
                val target = chain.getThisObject()
                val raw = (chain.getArg(0) as? List<*>)?.take(50).orEmpty()
                onMain {
                    native = runCatching {
                        val alerts = interactor.get(manager.get(target))
                        raw.mapNotNull { row ->
                            if (!rowClass.isInstance(row)) return@mapNotNull null
                            val key = (rowKey.invoke(row) as? String)?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            NativeRow(key, rowPackage.invoke(row) as String, rowUser.invoke(row) as Int,
                                liveNotification.invoke(alerts, key) as? StatusBarNotification,
                                readText(rowView.invoke(row) as? View))
                        }
                    }.getOrDefault(emptyList())
                    runtime?.changed()
                }
            }
            result
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun readText(root: View?): List<String> {
        val result = LinkedHashSet<String>()
        fun walk(v: View?, depth: Int) {
            if (v == null || depth < 0 || result.size >= 5 || v.visibility != View.VISIBLE) return
            if (v is android.widget.TextView) {
                v.text?.toString()?.trim()?.take(240)?.takeIf { it.isNotEmpty() }?.let(result::add)
            }
            if (v is ViewGroup) for (i in 0 until minOf(v.childCount, 24)) walk(v.getChildAt(i), depth - 1)
        }
        walk(root, 5)
        return result.toList()
    }

    private fun findAttachedCapsule(type: Class<*>): ViewGroup? =
        attachedViews(type::isInstance).firstOrNull { it.display?.displayId == android.view.Display.DEFAULT_DISPLAY } as? ViewGroup

    private fun attachedViews(matches: (View) -> Boolean): List<View> = runCatching {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmg.getMethod("getInstance").invoke(null)
        val roots = wmg.getMethod("getWindowViews").invoke(instance) as? List<*> ?: return emptyList()
        buildList {
            fun scan(root: View, depth: Int) {
                if (depth < 0) return
                if (matches(root)) add(root)
                if (root is ViewGroup) for (i in 0 until root.childCount) scan(root.getChildAt(i), depth - 1)
            }
            roots.filterIsInstance<View>().forEach { scan(it, 20) }
        }
    }.getOrDefault(emptyList())
}
