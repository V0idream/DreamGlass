package com.coloros16.liquidglass.island

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi

internal enum class MediaGlyph { PREVIOUS, PLAY, PAUSE, NEXT, LIKE, UNLIKE }

internal data class IslandAction(val label: String, val glyph: MediaGlyph? = null, val run: () -> Unit)
internal data class IslandProgressSegment(val length: Int, val color: Int)
internal data class IslandProgressPoint(val position: Int, val color: Int)
internal data class IslandProgress(val position: Int, val max: Int,
    val segments: List<IslandProgressSegment>, val points: List<IslandProgressPoint>)

internal data class IslandActivity(
    override val id: String,
    override val kind: IslandKind,
    val packageName: String,
    val title: String,
    val detail: String = "",
    val icon: Drawable? = null,
    val artwork: Bitmap? = null,
    val progress: Int? = null,
    val progressMax: Int = 100,
    val segmented: IslandProgress? = null,
    val actions: List<IslandAction> = emptyList(),
    val open: (() -> Unit)? = null,
    val media: MediaController? = null,
    val lyrics: IslandLyrics.Track? = null,
    val clockBase: Long? = null,
    val countdown: Boolean = false,
    val nativeKeys: Set<String> = emptySet(),
    override val changedAt: Long = SystemClock.elapsedRealtime(),
    override val expiresAt: Long = Long.MAX_VALUE,
    override val urgent: Boolean = false,
) : ScheduledIslandActivity

internal object IslandSources {
    fun notification(context: Context, sbn: StatusBarNotification, changedAt: Long, seconds: Int): IslandActivity? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            n.visibility == Notification.VISIBILITY_SECRET) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE).clean()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).clean().ifEmpty {
            extras.getCharSequence(Notification.EXTRA_TEXT).clean()
        }
        if (title.isEmpty() && text.isEmpty()) return null
        val pkg = sbn.packageName
        val code = IslandPolicy.code(text) ?: IslandPolicy.code(title)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS)
        val showsClock = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val ongoing = sbn.isOngoing
        val promoted = android.os.Build.VERSION.SDK_INT >= 36 &&
            n.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        val segmented = if (android.os.Build.VERSION.SDK_INT >= 36 &&
            extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("\$ProgressStyle") == true)
            segmentedProgress(context, n) else null
        val kind = when {
            n.category == Notification.CATEGORY_CALL -> IslandKind.CALL
            n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_STOPWATCH -> IslandKind.TIMER
            code != null -> IslandKind.CODE
            promoted || segmented != null || n.category == Notification.CATEGORY_NAVIGATION ||
                (ongoing && (max > 0 || n.category == Notification.CATEGORY_PROGRESS)) -> IslandKind.LIVE
            n.category == Notification.CATEGORY_MESSAGE -> IslandKind.MESSAGE
            else -> return null
        }
        val urgent = (kind == IslandKind.CALL || kind == IslandKind.TIMER) &&
            (n.fullScreenIntent != null || n.category == Notification.CATEGORY_ALARM && !showsClock ||
                kind == IslandKind.CALL && !showsClock)
        val expiresAt = if (kind == IslandKind.MESSAGE || kind == IslandKind.CODE)
            changedAt + seconds * 1000L else Long.MAX_VALUE
        val id = if (kind == IslandKind.MESSAGE && !n.shortcutId.isNullOrBlank())
            "$pkg:conversation:${n.shortcutId}" else sbn.key
        return IslandActivity(
            id, kind, pkg, if (kind == IslandKind.CODE) code!! else title.ifEmpty { text },
            if (kind == IslandKind.CODE || title.isEmpty()) "" else text,
            icon = runCatching { n.smallIcon?.loadDrawable(context) }.getOrNull(),
            progress = progress.takeIf { max > 0 }?.coerceIn(0, max),
            progressMax = max.coerceAtLeast(1),
            segmented = segmented,
            actions = n.actions.orEmpty().mapNotNull { action ->
                val pending = action.actionIntent ?: return@mapNotNull null
                val label = action.title.clean()
                if (!action.remoteInputs.isNullOrEmpty() || label.isEmpty()) null
                else IslandAction(label, run = open(pending))
            },
            open = n.contentIntent?.let(::open),
            clockBase = if (showsClock && n.`when` > 0)
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - n.`when`) else null,
            countdown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            nativeKeys = setOf(sbn.key),
            changedAt = changedAt, expiresAt = expiresAt, urgent = urgent,
        )
    }

    @RequiresApi(36)
    private fun segmentedProgress(context: Context, notification: Notification): IslandProgress? = runCatching {
        val style = Notification.Builder.recoverBuilder(context, notification).style as? Notification.ProgressStyle
            ?: return null
        val max = style.progressMax
        if (max <= 0 || style.isProgressIndeterminate) return null
        IslandProgress(style.progress.coerceIn(0, max), max,
            style.progressSegments.orEmpty().take(24).map { IslandProgressSegment(it.length, it.color) },
            style.progressPoints.orEmpty().take(32).map { IslandProgressPoint(it.position.coerceIn(0, max), it.color) })
    }.getOrNull()

    fun nativeActivity(
        context: Context, row: FluidIslandHook.NativeRow, sbn: StatusBarNotification?, changedAt: Long,
    ): IslandActivity? {
        val n = sbn?.notification
        val extras = n?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE).clean().ifEmpty { row.texts.firstOrNull().orEmpty() }
        if (title.isEmpty()) return null
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT).clean().ifEmpty {
            row.texts.filterNot { it == title }.take(3).joinToString("\n")
        }
        val max = extras?.getInt(Notification.EXTRA_PROGRESS_MAX) ?: 0
        val appIntent = runCatching { context.packageManager.getLaunchIntentForPackage(row.packageName) }.getOrNull()
        return IslandActivity(sbn?.key ?: "seedling:${row.key}", IslandKind.LIVE, row.packageName, title, text,
            icon = runCatching { n?.smallIcon?.loadDrawable(context) }.getOrNull(),
            progress = extras?.getInt(Notification.EXTRA_PROGRESS)?.takeIf { max > 0 }?.coerceIn(0, max),
            progressMax = max.coerceAtLeast(1),
            actions = n?.actions.orEmpty().mapNotNull { action ->
                val pending = action.actionIntent ?: return@mapNotNull null
                val label = action.title.clean()
                if (!action.remoteInputs.isNullOrEmpty() || label.isEmpty()) null else IslandAction(label, run = open(pending))
            },
            open = n?.contentIntent?.let(::open) ?: appIntent?.let { intent ->
                { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            nativeKeys = setOf(row.key), changedAt = changedAt,
        )
    }

    fun media(controller: MediaController, changedAt: Long, expiresAt: Long): IslandActivity? {
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState ?: return null
        val title = metadata.description.title.clean().ifEmpty {
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE).clean()
        }
        if (title.isEmpty()) return null
        val controls = controller.transportControls
        val actions = buildList {
            if (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                add(IslandAction("上一首", MediaGlyph.PREVIOUS) { controls.skipToPrevious() })
            val playing = state.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
            val mask = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
            if (state.actions and (mask or PlaybackState.ACTION_PLAY_PAUSE) != 0L)
                add(IslandAction(if (playing) "暂停" else "播放", if (playing) MediaGlyph.PAUSE else MediaGlyph.PLAY) {
                    if (playing) controls.pause() else controls.play()
                })
            if (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                add(IslandAction("下一首", MediaGlyph.NEXT) { controls.skipToNext() })
            state.customActions.firstOrNull {
                it.name.clean() in setOf("喜欢", "取消喜欢", "Like", "Unlike")
            }?.let { action ->
                val name = action.name.clean()
                add(IslandAction(name, if (name == "取消喜欢" || name == "Unlike") MediaGlyph.UNLIKE else MediaGlyph.LIKE) {
                    controls.sendCustomAction(action, null)
                })
            }
        }
        return IslandActivity(
            "media:${controller.packageName}", IslandKind.MEDIA, controller.packageName, title,
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).clean().ifEmpty {
                metadata.description.subtitle.clean()
            },
            artwork = metadata.description.iconBitmap,
            actions = actions, open = controller.sessionActivity?.let(::open),
            media = controller, changedAt = changedAt, expiresAt = expiresAt,
        )
    }

    private fun open(intent: PendingIntent): () -> Unit = { intent.send() }
    private fun CharSequence?.clean() = this?.toString()?.trim()?.take(600).orEmpty()
}
