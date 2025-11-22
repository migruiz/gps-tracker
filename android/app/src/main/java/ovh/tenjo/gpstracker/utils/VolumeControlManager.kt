package ovh.tenjo.gpstracker.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
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
            // Restore ring volume to maximum for whitelisted calls
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, AudioManager.FLAG_SHOW_UI)
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

            // Enable vibration
            startVibration()

            // Wake up and turn on the screen
            wakeUpScreen()

            Log.d(TAG, "Ringer, vibration, and screen enabled for incoming call")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling ringer", e)
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
     * Disable ringer after call
     */
    fun disableRingerAfterCall() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

            // Stop vibration
            stopVibration()

            // Release wake lock
            releaseWakeLock()

            Log.d(TAG, "Ringer, vibration disabled and screen released after call")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling ringer", e)
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
