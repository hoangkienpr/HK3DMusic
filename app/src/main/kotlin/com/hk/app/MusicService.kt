package com.hk.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The single source of truth for playback.
 *
 * The activity never creates its own player: it connects with a
 * [androidx.media3.session.MediaController]. That gives us working background
 * playback, a media notification, lock-screen / Bluetooth controls and a
 * continuous audio session for the equalizer and the visualizer.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playbackSpeed = 1.0f

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true // ExoPlayer handles audio focus automatically
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)

            // Pin the audio session instead of letting ExoPlayer allocate one when
            // the first audio sink opens. The equalizer / visualizer / web UI then
            // always talk about the same session, even for streamed (YouTube)
            // tracks and across track changes.
            val pinnedSession = runCatching {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .generateAudioSessionId()
            }.getOrDefault(C.AUDIO_SESSION_ID_UNSET)
            if (pinnedSession != C.AUDIO_SESSION_ID_UNSET) {
                runCatching { setAudioSessionId(pinnedSession) }
            }
        }
        player = exoPlayer
        audioSessionId = exoPlayer.audioSessionId

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(CMD_AUDIO_SESSION_ID, Bundle.EMPTY))
                        .add(SessionCommand(CMD_SET_PLAYBACK_SPEED, Bundle.EMPTY))
                        .add(SessionCommand(CMD_GET_PLAYBACK_SPEED, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    return when (customCommand.customAction) {
                        CMD_AUDIO_SESSION_ID -> {
                            val extras = Bundle().apply {
                                putInt(KEY_AUDIO_SESSION_ID, exoPlayer.audioSessionId)
                            }
                            Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, extras)
                            )
                        }
                        CMD_SET_PLAYBACK_SPEED -> {
                            val speed = args.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
                            setPlaybackSpeed(speed)
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        CMD_GET_PLAYBACK_SPEED -> {
                            val extras = Bundle().apply {
                                putFloat(KEY_PLAYBACK_SPEED, playbackSpeed)
                            }
                            Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS, extras)
                            )
                        }
                        else -> super.onCustomCommand(session, controller, customCommand, args)
                    }
                }
            })
            .build()
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 2.0f)
        val params = player?.playbackParameters ?: androidx.media3.common.PlaybackParameters(playbackSpeed)
        player?.playbackParameters = params.withSpeed(playbackSpeed)
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        const val CMD_AUDIO_SESSION_ID = "com.hk.app.GET_AUDIO_SESSION_ID"
        const val KEY_AUDIO_SESSION_ID = "audioSessionId"
        const val CMD_SET_PLAYBACK_SPEED = "com.hk.app.SET_PLAYBACK_SPEED"
        const val CMD_GET_PLAYBACK_SPEED = "com.hk.app.GET_PLAYBACK_SPEED"
        const val KEY_PLAYBACK_SPEED = "playbackSpeed"

        /** Audio session of the running playback, 0 when the service hasn't created the player yet. */
        @Volatile
        var audioSessionId: Int = 0
    }
}
