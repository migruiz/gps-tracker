package ovh.tenjo.gpstracker.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Build
import ovh.tenjo.gpstracker.config.AppConfig

class CallManager(private val context: Context) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    /**
     * Check if a phone number is whitelisted (Mom or Dad)
     */
    fun isWhitelistedNumber(phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) return false

        val cleanNumber = cleanPhoneNumber(phoneNumber)
        val cleanMomNumber = cleanPhoneNumber(AppConfig.MOM_PHONE_NUMBER)
        val cleanDadNumber = cleanPhoneNumber(AppConfig.DAD_PHONE_NUMBER)

        return cleanNumber == cleanMomNumber || cleanNumber == cleanDadNumber
    }

    /**
     * Clean phone number by removing spaces, dashes, and country codes for comparison
     */
    private fun cleanPhoneNumber(phoneNumber: String): String {
        return phoneNumber.replace(Regex("[\\s\\-+()]"), "")
            .takeLast(10) // Take last 10 digits for comparison
    }

    /**
     * End/reject an incoming call (requires appropriate permissions)
     */
    @Suppress("DEPRECATION")
    fun endCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager?.endCall() ?: false
            } else {
                // For older versions, we need to use TelephonyManager with reflection
                endCallReflection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            false
        }
    }

    /**
     * End call using reflection for older Android versions
     */
    private fun endCallReflection(): Boolean {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val telephonyClass = Class.forName(telephonyManager.javaClass.name)
            val method = telephonyClass.getDeclaredMethod("endCall")
            method.isAccessible = true
            method.invoke(telephonyManager)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call via reflection", e)
            false
        }
    }

    /**
     * Make a phone call to a specific number
     */
    fun makeCall(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Initiating call to: $phoneNumber")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: CALL_PHONE permission not granted", e)
            // Fall back to dialer if permission not granted
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
                Log.d(TAG, "Opened dialer for: $phoneNumber")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open dialer", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error making call to $phoneNumber", e)
        }
    }

    /**
     * Make a call to Mom
     */
    fun callMom() {
        makeCall(AppConfig.MOM_PHONE_NUMBER)
    }

    /**
     * Make a call to Dad
     */
    fun callDad() {
        makeCall(AppConfig.DAD_PHONE_NUMBER)
    }

    /**
     * Accept an incoming call
     */
    @Suppress("DEPRECATION")
    fun acceptCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager?.acceptRingingCall()
                true
            } else {
                acceptCallReflection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting call", e)
            false
        }
    }

    /**
     * Accept call using reflection for older Android versions
     */
    private fun acceptCallReflection(): Boolean {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val telephonyClass = Class.forName(telephonyManager.javaClass.name)
            val method = telephonyClass.getDeclaredMethod("answerRingingCall")
            method.isAccessible = true
            method.invoke(telephonyManager)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting call via reflection", e)
            false
        }
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
