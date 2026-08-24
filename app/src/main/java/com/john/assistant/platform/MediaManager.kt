package com.john.assistant.platform

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What is playing, as far as Android will say. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val appPackage: String,
    val isPlaying: Boolean,
)

/**
 * Media transport and volume.
 *
 * Android offers two routes and John uses both, in this order:
 *
 *  1. **MediaSession** — the correct API. It names the app that is playing,
 *     exposes track metadata, and lets John target a specific player. It needs
 *     notification-listener access, because `getActiveSessions` is gated behind
 *     it; there is no lesser permission that grants it.
 *  2. **Media key events** — `AudioManager.dispatchMediaKeyEvent`, the same
 *     thing a headset button sends. No permission at all, works with every
 *     player, but John gets no feedback and cannot choose which app responds.
 *
 * So notification access upgrades media control from "works" to "works and can
 * tell you what's playing". Nothing here screen-scrapes; media control never
 * needs accessibility.
 */
@Singleton
class MediaManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val sessionManager: MediaSessionManager?
        get() = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    // ------------------------------------------------------------- transport

    fun play(): Boolean = withController({ it.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)

    fun pause(): Boolean = withController({ it.pause() }, KeyEvent.KEYCODE_MEDIA_PAUSE)

    fun togglePlayPause(): Boolean {
        val controller = activeController()
        return if (controller != null) {
            if (isPlaying(controller)) controller.transportControls.pause()
            else controller.transportControls.play()
            true
        } else {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun stop(): Boolean = withController({ it.stop() }, KeyEvent.KEYCODE_MEDIA_STOP)

    fun next(): Boolean = withController({ it.skipToNext() }, KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous(): Boolean =
        withController({ it.skipToPrevious() }, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /**
     * Track metadata, or null when nothing is playing or John lacks
     * notification access. Callers must treat null as "I don't know", not as
     * "nothing is playing".
     */
    fun nowPlaying(): NowPlaying? {
        val controller = activeController() ?: return null
        val metadata = controller.metadata
        return NowPlaying(
            title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            appPackage = controller.packageName,
            isPlaying = isPlaying(controller),
        )
    }

    fun hasActiveSession(): Boolean = activeController() != null

    // ---------------------------------------------------------------- volume

    /** Current music volume as a percentage of the device maximum. */
    fun musicVolumePercent(): Int {
        val manager = audioManager ?: return 0
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setMusicVolumePercent(percent: Int): Boolean {
        val manager = audioManager ?: return false
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (percent.coerceIn(0, 100) * max + 50) / 100
        return runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        }.isSuccess
    }

    fun adjustMusicVolume(raise: Boolean): Boolean {
        val manager = audioManager ?: return false
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }.isSuccess
    }

    // -------------------------------------------------------------- internals

    /**
     * The session John should control: the one that is playing, else the most
     * recently active. Android returns sessions in priority order, so "first"
     * is already the right tie-break.
     */
    private fun activeController(): MediaController? {
        val manager = sessionManager ?: return null
        val listener = ComponentName(context, NOTIFICATION_LISTENER_CLASS)

        // Throws SecurityException without notification-listener access. That is
        // the expected state for a fresh install, not an error worth surfacing.
        val sessions = runCatching { manager.getActiveSessions(listener) }
            .getOrNull()
            .orEmpty()

        return sessions.firstOrNull { isPlaying(it) } ?: sessions.firstOrNull()
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING

    private inline fun withController(
        action: (MediaController.TransportControls) -> Unit,
        fallbackKeyCode: Int,
    ): Boolean {
        val controller = activeController()
        return if (controller != null) {
            runCatching { action(controller.transportControls) }.isSuccess
        } else {
            dispatchKey(fallbackKeyCode)
        }
    }

    /**
     * Send a media key as if from a headset.
     *
     * Both DOWN and UP are required: players that only see DOWN treat it as a
     * long-press, which on many of them means something else entirely.
     */
    private fun dispatchKey(keyCode: Int): Boolean {
        val manager = audioManager ?: return false
        return runCatching {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.isSuccess
    }

    private companion object {
        const val NOTIFICATION_LISTENER_CLASS =
            "com.john.assistant.services.JohnNotificationListenerService"
    }
}
