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
                        Log.d(TAG, "Error restricting background data for ${packageInfo.packageName}")
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
