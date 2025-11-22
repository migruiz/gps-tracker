package ovh.tenjo.gpstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Build
import ovh.tenjo.gpstracker.utils.CallManager
import ovh.tenjo.gpstracker.utils.VolumeControlManager

class PhoneCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneCallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var isIncomingCallFromWhitelist = false

        const val ACTION_INCOMING_CALL = "ovh.tenjo.gpstracker.INCOMING_CALL"
        const val ACTION_CALL_ENDED = "ovh.tenjo.gpstracker.CALL_ENDED"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        try {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

            // Try multiple ways to get the incoming number
            @Suppress("DEPRECATION")
            var incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            // If still null, try to get it from the telephony manager
            if (incomingNumber == null && state == TelephonyManager.EXTRA_STATE_RINGING) {
                try {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+ - we may not be able to get the number due to privacy restrictions
                        Log.w(TAG, "Cannot retrieve caller number on Android 12+ without proper permissions")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting phone number from telephony manager", e)
                }
            }

            Log.d(TAG, "Phone state changed: $state, number: $incomingNumber")

            val callManager = CallManager(context)
            val volumeManager = VolumeControlManager(context)

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Incoming call - notify MainActivity
                    broadcastIncomingCall(context)

                    if (incomingNumber.isNullOrBlank()) {
                        // If we can't get the number, for safety we should allow the call
                        // in case it's from Mom or Dad (better safe than sorry)
                        Log.w(TAG, "Cannot determine caller ID - allowing call to be safe")
                        isIncomingCallFromWhitelist = true
                        volumeManager.enableRingerForCall()
                    } else {
                        onIncomingCall(context, incomingNumber, callManager, volumeManager)
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Call answered or outgoing call
                    broadcastCallEnded(context)
                    onCallAnswered(context, volumeManager)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call ended
                    broadcastCallEnded(context)
                    onCallEnded(context, volumeManager)
                }
            }

            lastState = when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                else -> TelephonyManager.CALL_STATE_IDLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling phone call", e)
        }
    }

    private fun broadcastIncomingCall(context: Context) {
        val intent = Intent(ACTION_INCOMING_CALL)
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast: Incoming call")
    }

    private fun broadcastCallEnded(context: Context) {
        val intent = Intent(ACTION_CALL_ENDED)
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast: Call ended")
    }

    private fun onIncomingCall(
        context: Context,
        incomingNumber: String,
        callManager: CallManager,
        volumeManager: VolumeControlManager
    ) {
        Log.d(TAG, "Incoming call from: $incomingNumber")

        if (callManager.isWhitelistedNumber(incomingNumber)) {
            // Call from Mom or Dad - allow it and enable ringer
            Log.d(TAG, "Whitelisted number detected - allowing call")
            isIncomingCallFromWhitelist = true
            volumeManager.enableRingerForCall()
        } else {
            // Call from unknown number - block it
            Log.d(TAG, "Non-whitelisted number detected - blocking call")
            isIncomingCallFromWhitelist = false

            // End the call after a small delay to ensure system has processed it
            android.os.Handler(context.mainLooper).postDelayed({
                callManager.endCall()
            }, 500)
        }
    }

    private fun onCallAnswered(context: Context, volumeManager: VolumeControlManager) {
        Log.d(TAG, "Call answered")
        // Keep ringer enabled during call if it's from whitelist
        // The volume will be restored after call ends
    }

    private fun onCallEnded(context: Context, volumeManager: VolumeControlManager) {
        Log.d(TAG, "Call ended")

        // Restore muted state after call ends
        if (isIncomingCallFromWhitelist) {
            volumeManager.disableRingerAfterCall()
            isIncomingCallFromWhitelist = false
        }
    }
}
