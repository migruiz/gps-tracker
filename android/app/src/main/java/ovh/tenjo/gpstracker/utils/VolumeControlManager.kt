package ovh.tenjo.gpstracker.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.util.Log
import ovh.tenjo.gpstracker.admin.DeviceAdminReceiver

class VolumeControlManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    private var originalVolumes = mutableMapOf<Int, Int>()
    private var isVolumeMuted = false

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
     */
    fun enableRingerForCall() {
        try {
            // Temporarily restore ring volume
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val mediumVolume = (maxVolume * 0.7).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_RING, mediumVolume, 0)
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            Log.d(TAG, "Ringer enabled for incoming call")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling ringer", e)
        }
    }

    /**
     * Disable ringer after call
     */
    fun disableRingerAfterCall() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            Log.d(TAG, "Ringer disabled after call")
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
