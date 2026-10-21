/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Draws the Android 12 media notification on platforms older than API 31.
 *
 * Android 12 introduced the redesigned media notification (square artwork on the right, transport
 * controls on the left and the elapsed/total time around the scrubber) and renders it inside the
 * system UI. Older platforms still use the legacy media template, which ignores custom content
 * views, so the very same layout is drawn here with [RemoteViews].
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LegacyMediaNotificationProvider(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isLiked: () -> Boolean,
) : MediaNotification.Provider {
    private var mediaSession: MediaSession? = null
    private var actionFactory: MediaNotification.ActionFactory? = null
    private var notificationCallback: MediaNotification.Provider.Callback? = null
    private var playerListener: Player.Listener? = null
    private var progressJob: Job? = null
    private var artworkUri: Uri? = null
    private var artworkBitmap: Bitmap? = null
    private var likedOverride: Boolean? = null

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        this.mediaSession = mediaSession
        this.actionFactory = actionFactory
        this.notificationCallback = onNotificationChangedCallback
        observePlayer(mediaSession.player)
        return MediaNotification(MusicService.NOTIFICATION_ID, buildNotification(mediaSession))
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo(
            MusicService.CHANNEL_ID,
            context.getString(R.string.music_player),
        )

    /** Rebuilds and reposts the notification, e.g. after the liked state changed. */
    fun refresh(isLiked: Boolean? = null) {
        likedOverride = isLiked
        scope.launch { postNotification() }
    }

    private fun observePlayer(player: Player) {
        if (playerListener != null) return
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (events.containsAny(
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_MEDIA_METADATA_CHANGED,
                        )
                    ) {
                        refresh()
                    }
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        if (player.isPlaying) {
                            startProgressUpdates()
                        } else {
                            stopProgressUpdates()
                        }
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    refresh()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    refresh()
                }
            }
        playerListener = listener
        player.addListener(listener)
        if (player.isPlaying) startProgressUpdates()
    }

    private fun startProgressUpdates() {
        if (progressJob != null) return
        progressJob =
            scope.launch {
                while (isActive) {
                    delay(1.seconds)
                    postNotification()
                }
            }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun postNotification() {
        val session = mediaSession ?: return
        val callback = notificationCallback ?: return
        callback.onNotificationChanged(
            MediaNotification(MusicService.NOTIFICATION_ID, buildNotification(session)),
        )
    }

    private fun buildNotification(session: MediaSession): Notification {
        val player = session.player
        val metadata = player.mediaMetadata
        loadArtwork(session, metadata)

        // The collapsed notification is limited to about 48dp, so it only carries the transport
        // controls while the expanded one adds the scrubber and the secondary actions.
        val collapsed = RemoteViews(context.packageName, R.layout.notification_media)
        val expanded = RemoteViews(context.packageName, R.layout.notification_media_expanded)
        applyState(collapsed, player, metadata)
        applyState(expanded, player, metadata)
        applyProgress(expanded, player)

        return NotificationCompat
            .Builder(context, MusicService.CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(session.sessionActivity)
            .setDeleteIntent(actionFactory?.createNotificationDismissalIntent(session))
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun applyState(
        views: RemoteViews,
        player: Player,
        metadata: MediaMetadata,
    ) {
        views.setTextViewText(
            R.id.media_title,
            metadata.title ?: context.getString(R.string.music_player),
        )
        views.setTextViewText(R.id.media_artist, metadata.artist ?: "")

        views.setImageViewResource(
            R.id.media_play_pause,
            if (player.isPlaying) R.drawable.pause else R.drawable.play,
        )
        views.setImageViewResource(
            R.id.media_shuffle,
            if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle,
        )
        views.setImageViewResource(
            R.id.media_repeat,
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                else -> R.drawable.repeat
            },
        )
        views.setImageViewResource(
            R.id.media_like,
            if (likedOverride ?: isLiked()) R.drawable.ic_heart else R.drawable.ic_heart_outline,
        )

        views.setOnClickPendingIntent(
            R.id.media_previous,
            servicePendingIntent(MusicService.ACTION_NOTIFICATION_PREVIOUS, 0),
        )
        views.setOnClickPendingIntent(
            R.id.media_play_pause,
            servicePendingIntent(MusicService.ACTION_NOTIFICATION_PLAY_PAUSE, 1),
        )
        views.setOnClickPendingIntent(
            R.id.media_next,
            servicePendingIntent(MusicService.ACTION_NOTIFICATION_NEXT, 2),
        )
        views.setOnClickPendingIntent(
            R.id.media_shuffle,
            servicePendingIntent(MusicService.ACTION_NOTIFICATION_SHUFFLE, 3),
        )
        views.setOnClickPendingIntent(
            R.id.media_like,
            servicePendingIntent(MusicService.ACTION_NOTIFICATION_LIKE, 4),
        )
        views.setOnClickPendingIntent(
            R.id.media_repeat,
            servicePendingIntent(MusicService.ACTION_NOTIFICATION_REPEAT, 5),
        )

        artworkBitmap?.let { views.setImageViewBitmap(R.id.media_artwork, it) }
    }

    private fun applyProgress(
        views: RemoteViews,
        player: Player,
    ) {
        val duration = player.duration
        val position = player.currentPosition
        if (duration == C.TIME_UNSET || duration <= 0) {
            views.setViewVisibility(R.id.media_elapsed, View.GONE)
            views.setViewVisibility(R.id.media_progress, View.GONE)
            views.setViewVisibility(R.id.media_duration, View.GONE)
            return
        }
        views.setViewVisibility(R.id.media_elapsed, View.VISIBLE)
        views.setViewVisibility(R.id.media_progress, View.VISIBLE)
        views.setViewVisibility(R.id.media_duration, View.VISIBLE)
        views.setProgressBar(
            R.id.media_progress,
            PROGRESS_MAX,
            (position * PROGRESS_MAX / duration).toInt().coerceIn(0, PROGRESS_MAX),
            false,
        )
        views.setTextViewText(R.id.media_elapsed, formatTime(position))
        views.setTextViewText(R.id.media_duration, formatTime(duration))
    }

    private fun loadArtwork(
        session: MediaSession,
        metadata: MediaMetadata,
    ) {
        val uri = metadata.artworkUri
        if (uri == artworkUri) return
        artworkUri = uri
        artworkBitmap = null
        val future =
            try {
                session.bitmapLoader.loadBitmapFromMetadata(metadata)
            } catch (e: Exception) {
                null
            } ?: return
        future.addListener(
            {
                val bitmap =
                    try {
                        future.get()
                    } catch (e: Exception) {
                        null
                    } ?: return@addListener
                scope.launch {
                    if (artworkUri == uri) {
                        artworkBitmap = bitmap
                        postNotification()
                    }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun servicePendingIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private companion object {
        const val PROGRESS_MAX = 1000
    }
}
