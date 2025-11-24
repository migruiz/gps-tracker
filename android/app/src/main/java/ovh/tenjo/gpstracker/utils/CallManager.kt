package ovh.tenjo.gpstracker.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

        Log.d(TAG, "Comparing: $cleanNumber with Mom: $cleanMomNumber, Dad: $cleanDadNumber")
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
                val result = telecomManager?.endCall() ?: false
                Log.d(TAG, "End call result (API 28+): $result")
                result
            } else {
                // For older versions, we need to use TelephonyManager with reflection
                endCallReflection()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException ending call - missing permission", e)
            false
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
            Log.d(TAG, "End call via reflection succeeded")
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
        Log.d(TAG, "makeCall() called for: $phoneNumber")

        // Check if CALL_PHONE permission is granted
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "CALL_PHONE permission granted: $hasPermission")

        if (!hasPermission) {
            Log.e(TAG, "CALL_PHONE permission not granted, opening dialer instead")
            openDialer(phoneNumber)
            return
        }

        try {
            // Use telecom manager for DO mode if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecomManager != null) {
                val uri = Uri.fromParts("tel", phoneNumber, null)
                Log.d(TAG, "Using TelecomManager.placeCall() with URI: $uri")
                telecomManager.placeCall(uri, null)
                Log.d(TAG, "Call placed via TelecomManager")
                return
            }

            // Fallback to ACTION_CALL intent
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            Log.d(TAG, "Starting ACTION_CALL intent")
            context.startActivity(intent)
            Log.d(TAG, "Call initiated via Intent")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: CALL_PHONE permission issue", e)
            openDialer(phoneNumber)
        } catch (e: Exception) {
            Log.e(TAG, "Error making call to $phoneNumber", e)
            openDialer(phoneNumber)
        }
    }

    /**
     * Open dialer as fallback
     */
    private fun openDialer(phoneNumber: String) {
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            Log.d(TAG, "Opened dialer for: $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open dialer", e)
        }
    }

    /**
     * Make a call to Mom
     */
    fun callMom() {
        Log.d(TAG, "callMom() triggered")
        makeCall(AppConfig.MOM_PHONE_NUMBER)
    }

    /**
     * Make a call to Dad
     */
    fun callDad() {
        Log.d(TAG, "callDad() triggered")
        makeCall(AppConfig.DAD_PHONE_NUMBER)
    }

    /**
     * Accept an incoming call
     */
    @Suppress("DEPRECATION")
    fun acceptCall(): Boolean {
        Log.d(TAG, "acceptCall() called")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager?.acceptRingingCall()
                Log.d(TAG, "Call accepted via TelecomManager (API 26+)")
                true
            } else {
                val result = acceptCallReflection()
                Log.d(TAG, "Call accepted via reflection: $result")
                result
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException accepting call - missing permission", e)
            false
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
