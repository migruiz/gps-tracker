package ovh.tenjo.gpstracker.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import android.util.Log
import ovh.tenjo.gpstracker.admin.DeviceAdminReceiver

class VolumeControlManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var originalVolumes = mutableMapOf<Int, Int>()
    private var isVolumeMuted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Mute all volumes (notifications, sounds, media, ringer) when in DO mode
     */
    fun muteAllVolumes() {
        if (isVolumeMuted) return

        try {
            // Save original volumes
            originalVolumes[AudioManager.STREAM_MUSIC] = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            originalVolumes[AudioManager.STREAM_RING] = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            originalVolumes[AudioManager.STREAM_NOTIFICATION] = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            originalVolumes[AudioManager.STREAM_ALARM] = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            originalVolumes[AudioManager.STREAM_SYSTEM] = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)

            // Mute all streams
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)

            // Set ringer mode to silent
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

            isVolumeMuted = true
            Log.d(TAG, "All volumes muted")
        } catch (e: Exception) {
            Log.e(TAG, "Error muting volumes", e)
        }
    }

    /**
     * Restore original volumes
     */
    fun restoreVolumes() {
        if (!isVolumeMuted) return

        try {
            originalVolumes.forEach { (streamType, volume) ->
                audioManager.setStreamVolume(streamType, volume, 0)
            }

            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            isVolumeMuted = false
            originalVolumes.clear()
            Log.d(TAG, "Volumes restored")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring volumes", e)
        }
    }

    /**
     * Enable ringer for incoming calls from whitelisted numbers
     * Also enables vibration and wakes screen
     */
    fun enableRingerForCall() {
        try {
            Log.d(TAG, "enableRingerForCall() called")

            // Check and handle Do Not Disturb mode
            val interruptionFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.ringerMode
            } else {
                AudioManager.RINGER_MODE_NORMAL
            }
            Log.d(TAG, "Current ringer mode: $interruptionFilter")

            // Force ringer mode to normal first
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

            // Restore ring volume to maximum for whitelisted calls
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, AudioManager.FLAG_SHOW_UI)
            Log.d(TAG, "Ring volume set to max: $maxVolume")

            // Also set voice call volume to ensure we can hear during call
            val maxVoiceCallVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceCallVolume, 0)

            // Also set media volume as backup
            val maxMediaVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMediaVolume, 0)
            Log.d(TAG, "Media volume set to max: $maxMediaVolume")

            // Enable vibration first
            startVibration()

            // Wake up and turn on the screen
            wakeUpScreen()

            // Small delay to ensure audio system is ready, then play ringtone
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                playRingtone()
            }, 200)

            Log.d(TAG, "Ringer, vibration, and screen enabled for incoming call")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling ringer", e)
        }
    }

    /**
     * Request audio focus for ringtone playback
     */
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .build()

                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                Log.d(TAG, "Audio focus request result: $result (1=granted, 0=failed)")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.d(TAG, "Audio focus request result (legacy): $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus", e)
        }
    }

    /**
     * Abandon audio focus
     */
    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    Log.d(TAG, "Audio focus abandoned")
                }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
                Log.d(TAG, "Audio focus abandoned (legacy)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
    }

    /**
     * Play the default ringtone
     */
    private fun playRingtone() {
        try {
            Log.d(TAG, "playRingtone() called")

            // Stop any existing ringtone first
            stopRingtone()

            // Request audio focus RIGHT BEFORE playing
            requestAudioFocus()

            // Get default ringtone URI
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            Log.d(TAG, "Ringtone URI: $ringtoneUri")

            // Verify volume is still up
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val currentMode = audioManager.ringerMode
            Log.d(TAG, "Before playing - Ring volume: $currentVolume, Ringer mode: $currentMode")

            // Try using MediaPlayer for better control on Xiaomi devices
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, ringtoneUri)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_RING)
                    }

                    isLooping = true
                    prepare()
                    setVolume(1.0f, 1.0f)
                    start()
                }
                Log.d(TAG, "MediaPlayer started, isPlaying: ${mediaPlayer?.isPlaying}")
            } catch (e: Exception) {
                Log.e(TAG, "MediaPlayer failed, trying Ringtone API", e)

                // Fallback to Ringtone API
                ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                ringtone?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        it.audioAttributes = audioAttributes
                        it.isLooping = true
                    }

                    it.play()
                    Log.d(TAG, "Ringtone started playing, isPlaying: ${it.isPlaying}")
                } ?: run {
                    Log.e(TAG, "Failed to create ringtone object")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone", e)
        }
    }

    /**
     * Stop the ringtone
     */
    private fun stopRingtone() {
        try {
            // Stop MediaPlayer if it exists
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                    Log.d(TAG, "MediaPlayer stopped")
                }
                it.release()
                mediaPlayer = null
            }

            // Stop Ringtone if it exists
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                    Log.d(TAG, "Ringtone stopped")
                }
            }
            ringtone = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone", e)
        }
    }

    /**
     * Start vibration pattern for incoming call
     */
    private fun startVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Vibration pattern: wait 500ms, vibrate 1000ms, wait 500ms, vibrate 1000ms
                val timings = longArrayOf(500, 1000, 500, 1000)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                val vibrationEffect = VibrationEffect.createWaveform(timings, amplitudes, 0) // 0 = repeat
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                val pattern = longArrayOf(500, 1000, 500, 1000)
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0) // 0 = repeat
            }
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    /**
     * Stop vibration
     */
    private fun stopVibration() {
        try {
            vibrator.cancel()
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }

    /**
     * Wake up the screen and keep it on
     */
    private fun wakeUpScreen() {
        try {
            // Acquire wake lock to turn on screen
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "GPSTracker:IncomingCallWakeLock"
            )
            wakeLock?.acquire(60000) // Keep screen on for 60 seconds max
            Log.d(TAG, "Screen wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen", e)
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Screen wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    /**
     * Disable ringer after call and stop vibration/ringtone
     */
    fun disableRingerAfterCall() {
        try {
            // Stop ringtone
            stopRingtone()

            // Stop vibration
            stopVibration()

            // Abandon audio focus
            abandonAudioFocus()

            // Mute volume
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

            // Release wake lock
            releaseWakeLock()

            Log.d(TAG, "Ringer, ringtone, vibration disabled and screen released after call")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling ringer", e)
        }
    }

    /**
     * Stop ringing and vibration when call is answered
     */
    fun onCallAnswered() {
        try {
            // Stop ringtone
            stopRingtone()

            // Stop vibration
            stopVibration()

            // Abandon audio focus
            abandonAudioFocus()

            Log.d(TAG, "Ringtone and vibration stopped - call answered")
        } catch (e: Exception) {
            Log.e(TAG, "Error on call answered", e)
        }
    }

    /**
     * Disable volume buttons using Device Owner API
     */
    fun disableVolumeButtons() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner, cannot disable volume buttons")
            return
        }

        try {
            // Disable volume adjustment
            devicePolicyManager.setMasterVolumeMuted(adminComponent, true)
            Log.d(TAG, "Volume buttons disabled via Device Policy")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling volume buttons", e)
        }
    }

    /**
     * Enable volume buttons
     */
    fun enableVolumeButtons() {
        if (!isDeviceOwner()) {
            return
        }

        try {
            devicePolicyManager.setMasterVolumeMuted(adminComponent, false)
            Log.d(TAG, "Volume buttons enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling volume buttons", e)
        }
    }

    /**
     * Check if app is device owner
     */
    private fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    companion object {
        private const val TAG = "VolumeControlManager"
    }
}
