package com.example.spotads

import android.content.ComponentName
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

private const val TAG = "AdMuteListenerService"
private const val SPOTIFY_PACKAGE = "com.spotify.music"
private const val RESTORE_DEBOUNCE_MS = 400L

enum class AdMuteState { NORMAL, MUTED, PENDING_RESTORE, NO_SESSION }

/**
 * Watches Spotify's active MediaSession and mutes the music stream while an ad is
 * playing, restoring the mute once playback returns to normal.
 *
 * Uses AudioManager's own ADJUST_MUTE/ADJUST_UNMUTE flag rather than saving and
 * restoring a volume level: the system already tracks mute-vs-level as separate
 * state, so this can't drift if the user changes volume mid-ad, and re-muting an
 * already-muted stream (back-to-back ads) is naturally a no-op.
 */
class AdMuteListenerService : NotificationListenerService() {

    /** Lightweight status hook a future UI can attach to without any extra dependency. */
    fun interface StatusListener {
        fun onStatusChanged(state: AdMuteState, event: String)
    }

    companion object {
        @Volatile var currentState: AdMuteState = AdMuteState.NO_SESSION
            private set
        @Volatile var lastEvent: String? = null
            private set
        @Volatile var todayAdCount: Int = 0
            private set
        @Volatile var lifetimeAdCount: Int = 0
            private set

        private var listener: StatusListener? = null

        fun setStatusListener(l: StatusListener?) {
            listener = l
            l?.onStatusChanged(currentState, lastEvent ?: "")
        }

        private fun report(state: AdMuteState, event: String) {
            currentState = state
            lastEvent = event
            Log.d(TAG, event)
            listener?.onStatusChanged(state, event)
        }

        private fun updateCounts(today: Int, lifetime: Int) {
            todayAdCount = today
            lifetimeAdCount = lifetime
        }
    }

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var audioManager: AudioManager
    private lateinit var componentName: ComponentName
    private val handler = Handler(Looper.getMainLooper())

    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    // True only while we are the ones holding the mute (never true if the user
    // muted manually) — restore must only ever undo a mute we caused ourselves.
    private var weMuted = false
    private var pendingAdCount = 0
    private var lastAdSignature: String? = null
    private var restoreRunnable: Runnable? = null

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        attachToSpotifySession()
    }

    // Lets the master switch take effect immediately, including force-restoring
    // if the user disables muting while an ad currently has the stream muted.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (AdMutePrefs.isEnabledKey(key) && !AdMutePrefs.isEnabled(applicationContext)) {
            forceRestoreIfMuted()
            report(AdMuteState.NORMAL, "Muting disabled by user — restored")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        componentName = ComponentName(this, AdMuteListenerService::class.java)

        updateCounts(AdMutePrefs.todayMutedCount(applicationContext), AdMutePrefs.lifetimeMutedCount(applicationContext))
        AdMutePrefs.registerChangeListener(applicationContext, prefsListener)

        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
        attachToSpotifySession()
    }

    override fun onListenerDisconnected() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        AdMutePrefs.unregisterChangeListener(applicationContext, prefsListener)
        detachController()
        forceRestoreIfMuted()
        report(AdMuteState.NO_SESSION, "Listener disconnected")
        super.onListenerDisconnected()
    }

    private fun attachToSpotifySession() {
        val sessions = try {
            mediaSessionManager.getActiveSessions(componentName)
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions denied — listener not fully connected yet", e)
            return
        }
        val spotifySession = sessions.firstOrNull { it.packageName == SPOTIFY_PACKAGE }

        if (spotifySession?.sessionToken == activeController?.sessionToken) {
            return
        }

        detachController()

        if (spotifySession == null) {
            forceRestoreIfMuted()
            report(AdMuteState.NO_SESSION, "No active Spotify session")
            return
        }

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                handleMetadataChanged(metadata)
            }

            override fun onPlaybackStateChanged(playbackState: PlaybackState?) {
                if (playbackState?.state == PlaybackState.STATE_NONE) {
                    forceRestoreIfMuted()
                    report(AdMuteState.NO_SESSION, "Playback state NONE — force-restored")
                }
            }

            override fun onSessionDestroyed() {
                forceRestoreIfMuted()
                activeController = null
                report(AdMuteState.NO_SESSION, "Spotify session destroyed — force-restored")
            }
        }

        spotifySession.registerCallback(callback, handler)
        activeController = spotifySession
        controllerCallback = callback

        handleMetadataChanged(spotifySession.metadata)
    }

    private fun detachController() {
        activeController?.let { controller ->
            controllerCallback?.let { controller.unregisterCallback(it) }
        }
        activeController = null
        controllerCallback = null
    }

    private fun isAdMetadata(metadata: MediaMetadata?): Boolean {
        if (metadata == null) return false
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        return artist?.lowercase()?.contains("advertisement") == true ||
            title?.lowercase()?.contains("advertisement") == true
    }

    // Identifies which ad a piece of metadata belongs to. Spotify fires
    // onMetadataChanged many times per ad slide (artwork loading, position
    // updates, etc.) with the same artist/title — without this, every one of
    // those repeats was being counted and logged as a brand-new ad.
    private fun adSignature(metadata: MediaMetadata?): String {
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        return "$artist|$title|$duration"
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (!AdMutePrefs.isEnabled(applicationContext)) {
            return
        }
        if (isAdMetadata(metadata)) {
            cancelPendingRestore()
            val signature = adSignature(metadata)
            if (weMuted && signature == lastAdSignature) {
                // Same ad re-posting metadata, not a new ad — nothing to count or log.
                return
            }
            lastAdSignature = signature
            pendingAdCount++
            updateCounts(AdMutePrefs.incrementMutedCount(applicationContext), AdMutePrefs.lifetimeMutedCount(applicationContext))
            if (!weMuted) {
                muteMusicStream()
            }
            report(AdMuteState.MUTED, "Ad detected (#$pendingAdCount in this break, $todayAdCount today) — muted")
        } else if (weMuted) {
            schedulePendingRestore()
        }
    }

    private fun muteMusicStream() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        weMuted = true
    }

    private fun schedulePendingRestore() {
        cancelPendingRestore()
        if (currentState != AdMuteState.PENDING_RESTORE) {
            report(AdMuteState.PENDING_RESTORE, "Ad metadata cleared — debouncing restore")
        }
        val runnable = Runnable {
            restoreVolume()
            pendingAdCount = 0
            lastAdSignature = null
            report(AdMuteState.NORMAL, "Restored after debounce")
        }
        restoreRunnable = runnable
        handler.postDelayed(runnable, RESTORE_DEBOUNCE_MS)
    }

    private fun cancelPendingRestore() {
        restoreRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = null
    }

    private fun restoreVolume() {
        if (weMuted) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            weMuted = false
        }
    }

    private fun forceRestoreIfMuted() {
        cancelPendingRestore()
        restoreVolume()
        pendingAdCount = 0
        lastAdSignature = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // No-op: ad detection runs entirely off MediaSession callbacks, not notifications.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op.
    }
}
