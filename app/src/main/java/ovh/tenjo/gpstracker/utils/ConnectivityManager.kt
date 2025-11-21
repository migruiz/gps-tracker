package ovh.tenjo.gpstracker.utils

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import ovh.tenjo.gpstracker.admin.DeviceAdminReceiver
import java.lang.reflect.Method

class ConnectivityManager(private val context: Context) {

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val connectivityManager: android.net.ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(context, DeviceAdminReceiver::class.java)
    }

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    @SuppressLint("MissingPermission")
    fun setMobileDataEnabled(enabled: Boolean) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot control mobile data - not device owner")
            return
        }

        try {
            // Use reflection to access hidden API for mobile data control
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val method: Method = android.net.ConnectivityManager::class.java.getDeclaredMethod(
                    "setMobileDataEnabled",
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(connectivityManager, enabled)
                Log.d(TAG, "Mobile data ${if (enabled) "enabled" else "disabled"}")
            } else {
                Log.w(TAG, "Mobile data control not supported on this API level")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting mobile data state: ${e.message}", e)
            // Fallback: Try using Settings.Global (requires WRITE_SECURE_SETTINGS)
            try {
                Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data",
                    if (enabled) 1 else 0
                )
                Log.d(TAG, "Mobile data ${if (enabled) "enabled" else "disabled"} via Settings")
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback mobile data control also failed", e2)
            }
        }
    }

    fun setWifiEnabled(enabled: Boolean) {
        try {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enabled
            Log.d(TAG, "WiFi ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting WiFi state", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun setAirplaneMode(enabled: Boolean) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot control airplane mode - not device owner")
            return
        }

        try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (enabled) 1 else 0
            )

            // Broadcast the change
            val intent = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", enabled)
            context.sendBroadcast(intent)

            Log.d(TAG, "Airplane mode ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting airplane mode", e)
        }
    }

    @Suppress("DEPRECATION")
    fun isWifiConnected(): Boolean {
        return try {
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected &&
                   networkInfo.type == android.net.ConnectivityManager.TYPE_WIFI
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi connection", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    fun isMobileDataConnected(): Boolean {
        return try {
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected &&
                   networkInfo.type == android.net.ConnectivityManager.TYPE_MOBILE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mobile data connection", e)
            false
        }
    }

    fun restrictBackgroundData() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot restrict background data - not device owner")
            return
        }

        try {
            // Get all installed packages and restrict their background data
            @Suppress("DEPRECATION")
            val packages = context.packageManager.getInstalledApplications(0)
            for (packageInfo in packages) {
                if (packageInfo.packageName != context.packageName) {
                    try {
                        devicePolicyManager.setApplicationRestrictions(
                            adminComponent,
                            packageInfo.packageName,
                            android.os.Bundle().apply {
                                putBoolean("background_data", false)
                            }
                        )
                    } catch (_: Exception) {
                        // Some system packages may not allow restrictions
                    }
                }
            }
            Log.d(TAG, "Restricted background data for other apps")
        } catch (e: Exception) {
            Log.e(TAG, "Error restricting background data", e)
        }
    }

    fun enableKioskMode() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot enable kiosk mode - not device owner")
            return
        }

        try {
            // Set this app as lock task package
            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                arrayOf(context.packageName)
            )

            // Disable keyguard (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                devicePolicyManager.setKeyguardDisabled(adminComponent, true)
            }

            // Disable status bar (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                devicePolicyManager.setStatusBarDisabled(adminComponent, true)
            }

            Log.d(TAG, "Kiosk mode enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling kiosk mode", e)
        }
    }

    companion object {
        private const val TAG = "ConnectivityManager"
    }
}
