package com.coloros16.liquidglass.island

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.Toast
import com.coloros16.liquidglass.diagnostics.RuntimeDiagnostics

internal class IslandRuntime(
    private val anchor: View,
    private val prefs: SharedPreferences,
) : IslandWindow.Callbacks {
    private data class Posted(val sbn: StatusBarNotification, val at: Long) {
        private var read = false
        private var activity: IslandActivity? = null
        fun event(context: Context, seconds: Int): IslandActivity? {
            if (!read) {
                activity = IslandSources.notification(context, sbn, at, seconds)
                read = true
            }
            return activity?.let {
                if (it.expiresAt == Long.MAX_VALUE) it else it.copy(expiresAt = at + seconds * 1000L)
            }
        }
    }
    private data class Session(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var changed: Long,
        var paused: Long?,
        var wasActive: Boolean,
    )

    private val context = anchor.context
    private val main = Handler(Looper.getMainLooper())
    private val foreground = IslandForeground(context, anchor.javaClass.classLoader!!, main, ::changed)
    private val window = IslandWindow(context, prefs, this)
    private val notifications = LinkedHashMap<String, Posted>()
    private val sessions = LinkedHashMap<MediaSession.Token, Session>()
    private val media = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val hidden = LinkedHashSet<String>()
    private var items = emptyList<IslandActivity>()
    private var selected: String? = null
    private var selectedAt = 0L
    private var expandedActivity: String? = null
    private var touchingCard = false
    private var charging: Int? = null
    private var headphones = false
    private var recording = false
    private var userId: Int? = currentUser()
    private var stopped = false
    private var report = ""
    private var statusBlockedUntil = 0L
    private var screenOff = !power.isInteractive
    private data class LyricCache(val name: String, val artist: String, val track: IslandLyrics.Track?)
    private val lyrics = HashMap<String, LyricCache>()
    private val refresh = Runnable(::render)
    private val expiry = Runnable(::changed)
    private val collapse = Runnable(::collapseCard)

    private val mediaUpdates = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateSessions(controllers.orEmpty())
    }
    private val audioUpdates = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = audioChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = audioChanged()
    }
    private val recordingUpdates = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>?) {
            val live = configs.orEmpty().any { !it.isClientSilenced }
            if (recording == live) return
            recording = live
            if (!recording) hidden.remove("status:recording")
            changed()
        }
    }
    private val layoutChanged = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (l != ol || t != ot || r != or || b != ob) changed()
    }
    private val systemUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOff = true; suspendOverlay(restoreNative = true) }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { screenOff = false; nativeChanged() }
                "android.intent.action.USER_SWITCHED" -> {
                    suspendOverlay(restoreNative = true)
                    userId = currentUser()
                    notifications.clear()
                    hidden.clear()
                    selected = null
                    updateSessions(emptyList())
                    FluidIslandHook.refreshActiveNotifications()
                    loadSessions()
                    changed()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val now = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
                    charging = if (now in setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL) &&
                        level >= 0 && scale > 0) level * 100 / scale else null
                    if (charging == null) hidden.remove("status:charge")
                    changed()
                }
                else -> changed()
            }
        }
    }

    fun start(listener: NotificationListenerService?) {
        anchor.addOnLayoutChangeListener(layoutChanged)
        runCatching {
            context.registerReceiver(systemUpdates, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT); addAction("android.intent.action.USER_SWITCHED")
                addAction(Intent.ACTION_CONFIGURATION_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }, Context.RECEIVER_NOT_EXPORTED)
        }
        runCatching { audio.registerAudioDeviceCallback(audioUpdates, main) }
        configureRecording()
        runCatching { media.addOnActiveSessionsChangedListener(mediaUpdates, null, main) }
        loadSessions()
        refreshNotifications(listener)
        status("正在监听本机活动，锁屏与息屏保留系统显示")
        nativeChanged()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        foreground.close()
        suspendOverlay(restoreNative = true)
        anchor.removeOnLayoutChangeListener(layoutChanged)
        runCatching { context.unregisterReceiver(systemUpdates) }
        runCatching { audio.unregisterAudioDeviceCallback(audioUpdates) }
        runCatching { audio.unregisterAudioRecordingCallback(recordingUpdates) }
        runCatching { media.removeOnActiveSessionsChangedListener(mediaUpdates) }
        sessions.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
        sessions.clear()
        notifications.clear()
    }

    fun refreshNotifications(listener: NotificationListenerService?) {
        val current = runCatching { listener?.activeNotifications }.getOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        notifications.clear()
        current.asSequence().filter(::currentUser).sortedByDescending { it.postTime }.take(180).forEach {
            notifications[it.key] = Posted(it, now - (wall - it.postTime).coerceIn(0L, 86_400_000L))
        }
        changed()
    }

    fun onNotification(sbn: StatusBarNotification, removed: Boolean) {
        if (stopped || !currentUser(sbn)) return
        if (removed) {
            notifications.remove(sbn.key)
            hidden.remove(sbn.key)
        } else {
            val now = SystemClock.elapsedRealtime()
            notifications[sbn.key] = Posted(sbn, now)
            if (notifications.size > 180) notifications.remove(notifications.keys.first())
        }
        changed()
    }

    fun changed() {
        if (stopped) return
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    fun preferencesChanged() {
        lyrics.clear()
        configureRecording()
        changed()
    }

    fun sceneChanged(blocked: Boolean) {
        if (blocked) suspendOverlay(restoreNative = !screenAllowsTakeover())
        nativeChanged()
    }

    fun nativeChanged() {
        if (stopped) return
        if (screenAllowsTakeover() && SystemClock.elapsedRealtime() >= statusBlockedUntil) FluidIslandHook.ownNative()
        changed()
    }

    private var observingRecording = false
    private fun configureRecording() {
        val requested = IslandPrefs.show(prefs, IslandKind.STATE) && prefs.getBoolean(IslandPrefs.RECORDING, false)
        if (requested == observingRecording) return
        if (requested) {
            observingRecording = runCatching { audio.registerAudioRecordingCallback(recordingUpdates, main) }.isSuccess
            if (observingRecording) runCatching { recordingUpdates.onRecordingConfigChanged(audio.activeRecordingConfigurations) }
        } else {
            runCatching { audio.unregisterAudioRecordingCallback(recordingUpdates) }
            observingRecording = false
            recording = false
        }
    }

    private fun render() = runCatching { renderCurrent() }.onFailure {
        suspendOverlay(restoreNative = true)
        statusBlockedUntil = SystemClock.elapsedRealtime() + 4_000
        status("绘制窗口失败，已恢复原流体云，稍后重试")
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, 4_000)
    }

    private fun screenAllowsTakeover(): Boolean = IslandPolicy.suppressNative(
        enabled = !stopped,
        unlocked = !screenOff && power.isInteractive && !keyguard.isKeyguardLocked && !FluidIslandHook.inKeyguard(),
        hostAttached = anchor.isAttachedToWindow,
    )

    private fun renderCurrent() {
        val now = SystemClock.elapsedRealtime()
        if (stopped || now < statusBlockedUntil) return
        if (!screenAllowsTakeover()) {
            suspendOverlay(restoreNative = true)
            status("当前场景使用系统流体云")
            return
        }
        check(FluidIslandHook.ownNative())
        if (userId == null || FluidIslandHook.inShade() ||
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            !prefs.getBoolean(IslandPrefs.LANDSCAPE, false)) {
            foreground.watch(false)
            suspendOverlay()
            status("已隐藏系统流体云，接管窗口已收起")
            return
        }
        val secs = prefs.getInt(IslandPrefs.MESSAGE_SECONDS, 9).coerceIn(3, 60)
        val mediaEvents = sessions.values.mapNotNull { event(it, now) }
        val rows = FluidIslandHook.rows().filter { it.userId == userId }
        val mapped = rows.mapNotNull { row ->
            val sbn = row.notification?.takeIf(::currentUser) ?: notifications[row.key]?.sbn
            if (sbn?.notification?.visibility == android.app.Notification.VISIBILITY_SECRET) return@mapNotNull null
            val normal = notifications[row.key]?.event(context, secs)
                ?: sbn?.let { IslandSources.notification(context, it, now, secs) }
            val nativeMedia = sbn?.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true
            val stockMedia = mediaEvents.firstOrNull { it.packageName == row.packageName && (nativeMedia || it.title in row.texts) }
            val stock = stockMedia ?: normal ?: IslandSources.nativeActivity(context, row, sbn, now)?.let {
                if (nativeMedia) it.copy(kind = IslandKind.MEDIA) else it
            }
            stock?.copy(nativeKeys = stock.nativeKeys + row.key)
        }
        val events = LinkedHashMap<String, IslandActivity>()
        fun put(event: IslandActivity) {
            val existing = events[event.id]
            events[event.id] = if (existing == null) event else {
                val newer = if (event.changedAt >= existing.changedAt) event else existing
                newer.copy(nativeKeys = existing.nativeKeys + event.nativeKeys)
            }
        }
        mapped.forEach(::put)
        val notificationKeys = mapped.flatMap { it.nativeKeys }.toSet()
        for ((key, received) in notifications) {
            if (key !in notificationKeys) received.event(context, secs)?.let(::put)
        }
        mediaEvents.forEach(::put)
        if (prefs.getBoolean(IslandPrefs.CHARGE, true)) charging?.let {
            put(IslandActivity("status:charge", IslandKind.STATE, "", "充电 · $it%", progress = it, changedAt = 0))
        }
        if (headphones && prefs.getBoolean(IslandPrefs.HEADPHONES, true))
            put(IslandActivity("status:audio", IslandKind.STATE, "", "耳机已连接", changedAt = 0))
        if (recording) put(IslandActivity("status:recording", IslandKind.STATE, "", "麦克风使用中", changedAt = 0))
        val valid = events.values.filter { permitted(it) && it.expiresAt > now }
        hidden.retainAll(valid.map { it.id }.toSet())
        foreground.watch(valid.any { it.kind == IslandKind.MEDIA })
        main.removeCallbacks(expiry)
        valid.map { it.expiresAt }.filter { it != Long.MAX_VALUE }.minOrNull()?.let {
            main.postDelayed(expiry, (it - now).coerceAtLeast(1))
        }
        val inPlaybackApp = valid.any { IslandPolicy.isPlaybackApp(it.kind, it.packageName, foreground.packageName) }
        val eligible = if (inPlaybackApp) valid.filter { it.urgent } else valid
        val chosen = eligible.firstOrNull { it.id == selected }
        if (chosen == null || eligible.any {
            it.changedAt > selectedAt && it.id !in hidden && it.kind.priority > chosen.kind.priority
        }) selected = null
        var ordered = IslandPolicy.order(eligible, hidden, now, selected)
        if (ordered.isEmpty() && eligible.any { it.id in hidden }) {
            ordered = listOf(IslandActivity("recall", IslandKind.STATE, "", "召回活动", changedAt = 0))
        }
        val next = ordered.firstOrNull()
        if (next == null) {
            suspendOverlay()
            status(if (inPlaybackApp) "已隐藏播放应用前台的流体云窗口" else "已隐藏系统流体云，等待可显示的活动")
            return
        }
        if (expandedActivity != null && expandedActivity != next.id) {
            expandedActivity = null
            touchingCard = false
            main.removeCallbacks(collapse)
        }
        val expanded = expandedActivity != null
        items = ordered
        IslandGlass.enabled(expanded && prefs.getBoolean(IslandPrefs.GLASS, false) && !power.isPowerSaveMode)
        val animate = !power.isPowerSaveMode && android.animation.ValueAnimator.areAnimatorsEnabled()
        check(window.render(ordered, expanded, anchor.rootWindowInsets, animate))
        IslandGlass.setWindows(window.roots())
        if (window.isReady()) {
            status(if (expanded) "正在显示展开卡片，系统流体云已隐藏" else "接管中，胶囊已收起，系统流体云已隐藏")
        }
    }

    private fun permitted(activity: IslandActivity): Boolean =
        IslandPrefs.show(prefs, activity.kind) &&
            activity.packageName !in IslandPrefs.packages(prefs.getString(IslandPrefs.EXEMPT, null)) &&
            (activity.kind !in setOf(IslandKind.MESSAGE, IslandKind.CODE) || messageAllowed(activity.packageName))

    private fun messageAllowed(pkg: String): Boolean {
        val allow = IslandPrefs.packages(prefs.getString(IslandPrefs.FILTER, null))
        return pkg in allow
    }

    private fun loadSessions() {
        if (userId == 0) runCatching { updateSessions(media.getActiveSessions(null)) }
    }

    private fun updateSessions(controllers: List<MediaController>) {
        if (stopped || userId != 0 && controllers.isNotEmpty()) return
        val next = controllers.map { it.sessionToken }.toSet()
        (sessions.keys - next).forEach { token ->
            val session = sessions.remove(token) ?: return@forEach
            runCatching { session.controller.unregisterCallback(session.callback) }
            hidden.remove("media:${session.controller.packageName}")
        }
        for (controller in controllers) {
            val key = controller.sessionToken
            if (sessions.containsKey(key)) continue
            val session = Session(controller, object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = mediaChanged(key)
                override fun onPlaybackStateChanged(state: PlaybackState?) = mediaChanged(key)
                override fun onSessionDestroyed() { loadSessions(); changed() }
            }, SystemClock.elapsedRealtime(), null, mediaActive(controller))
            if (runCatching { controller.registerCallback(session.callback, main) }.isSuccess) sessions[key] = session
        }
        changed()
    }

    private fun mediaChanged(key: MediaSession.Token) {
        val state = sessions[key] ?: return
        state.changed = SystemClock.elapsedRealtime()
        if (mediaActive(state.controller)) {
            state.paused = null
            state.wasActive = true
        } else if (state.wasActive && state.paused == null) state.paused = state.changed
        changed()
    }

    private fun event(session: Session, now: Long): IslandActivity? {
        val active = mediaActive(session.controller)
        val until = if (active) Long.MAX_VALUE else
            (session.paused ?: return null) + prefs.getInt(IslandPrefs.MEDIA_LINGER, 10).coerceIn(0, 60) * 1000L
        if (until <= now) return null
        val source = IslandSources.media(session.controller, session.changed, until) ?: return null
        val entry = lyrics[source.id]?.takeIf { it.name == source.title && it.artist == source.detail }
            ?: LyricCache(source.title, source.detail, IslandLyrics.load(prefs, source.title, source.detail)).also {
                lyrics[source.id] = it
            }
        return source.copy(lyrics = entry.track)
    }

    private fun mediaActive(controller: MediaController) = controller.playbackState?.state in
        setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)

    private fun audioChanged() {
        val enabled = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)
        } }.getOrDefault(false)
        if (enabled == headphones) return
        headphones = enabled
        if (!enabled) hidden.remove("status:audio")
        changed()
    }

    private fun currentUser(sbn: StatusBarNotification): Boolean =
        runCatching { UserHandle.getUserHandleForUid(sbn.uid) == userId?.let {
            // Match the real current user's UID namespace without displaying work-profile data.
            UserHandle.getUserHandleForUid(it * 100_000)
        } }.getOrDefault(false)

    private fun currentUser(): Int? = runCatching {
        ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
    }.getOrNull()

    private fun suspendOverlay(restoreNative: Boolean = false) {
        if (restoreNative) foreground.watch(false)
        IslandGlass.setWindows(emptyList())
        window.clear()
        if (restoreNative) FluidIslandHook.release()
        expandedActivity = null
        touchingCard = false
        main.removeCallbacks(collapse)
    }

    override fun toggle() {
        val current = items.firstOrNull() ?: return
        if (current.id == "recall") { restore(); return }
        if (expandedActivity != null) {
            collapseCard()
        } else {
            expandedActivity = current.id
            interacted()
            changed()
        }
    }
    override fun collapseCard() {
        if (expandedActivity == null) return
        expandedActivity = null
        touchingCard = false
        main.removeCallbacks(collapse)
        changed()
    }
    override fun page(direction: Int) {
        if (items.size < 2 || items.first().urgent) return
        val rest = items.sortedWith(compareByDescending<IslandActivity> { it.kind.priority }
            .thenByDescending { it.changedAt }.thenBy { it.id })
        val position = rest.indexOfFirst { it.id == items[0].id }
        select(rest[(position + direction + rest.size) % rest.size].id)
    }
    override fun dismiss() {
        val item = items.firstOrNull()?.takeUnless { it.urgent || it.id == "recall" } ?: return
        hidden.add(item.id)
        selected = null
        collapseCard()
        changed()
    }
    override fun select(id: String) {
        if (expandedActivity != null) expandedActivity = id
        selected = id
        selectedAt = SystemClock.elapsedRealtime()
        interacted()
        changed()
    }
    override fun restore() {
        if (expandedActivity != null) { collapseCard(); return }
        hidden.clear()
        changed()
    }
    override fun interacted() {
        main.removeCallbacks(collapse)
        if (expandedActivity == null || touchingCard) return
        val stored = prefs.getInt(IslandPrefs.AUTO_COLLAPSE, IslandPrefs.DEFAULT_AUTO_COLLAPSE)
        main.postDelayed(collapse, IslandPrefs.autoCollapseSeconds(stored) * 1000L)
    }
    override fun cardTouchChanged(touching: Boolean) {
        touchingCard = touching
        interacted()
    }
    override fun attached() {
        if (stopped) return
        val success = runCatching {
            check(window.layoutReady() && screenAllowsTakeover())
            window.reveal()
            IslandGlass.refresh()
            window.isReady()
        }.getOrDefault(false)
        if (success) changed() else {
            suspendOverlay(restoreNative = true)
            statusBlockedUntil = SystemClock.elapsedRealtime() + 4_000
            status("独立窗口暂不可用，已恢复原流体云，稍后重试")
            main.removeCallbacks(expiry)
            main.postDelayed(expiry, 4_000)
        }
    }
    override fun surfaceRemoved(view: View) = IslandGlass.removeWindow(view)
    private fun status(value: String) {
        if (report == value) return
        report = value
        RuntimeDiagnostics.update(IslandPrefs.STATUS, value)
    }
}
