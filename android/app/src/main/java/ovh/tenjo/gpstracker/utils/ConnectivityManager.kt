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

            var restrictedCount = 0
            for (packageInfo in packages) {
                if (packageInfo.packageName != context.packageName) {
                    try {
                        // Restrict background data
                        devicePolicyManager.setApplicationRestrictions(
                            adminComponent,
                            packageInfo.packageName,
                            android.os.Bundle().apply {
                                putBoolean("background_data", false)
                            }
                        )

                        // Also add to restricted background apps list (API 21+)
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                devicePolicyManager.addUserRestriction(
                                    adminComponent,
                                    "no_config_vpn"
                                )
                            }
                        } catch (e: Exception) {
                            // Ignore if already set
                        }

                        restrictedCount++
                    } catch (_: Exception) {
                        // Some system packages may not allow restrictions
                    }
                }
            }
            Log.d(TAG, "Restricted background data for $restrictedCount apps")
        } catch (e: Exception) {
            Log.e(TAG, "Error restricting background data", e)
        }
    }

    /**
     * Apply aggressive power and performance restrictions to minimize CPU usage
     * XIAOMI-SAFE: Conservative approach to avoid system instability
     */
    fun applyAggressivePowerRestrictions() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot apply power restrictions - not device owner")
            return
        }

        try {
            // Disable automatic time zone (saves CPU from network checks)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    devicePolicyManager.setAutoTimeRequired(adminComponent, false)
                    Log.d(TAG, "Disabled automatic time updates")
                } catch (e: Exception) {
                    Log.d(TAG, "Could not disable auto time: ${e.message}")
                }
            }

            // Add CONSERVATIVE user restrictions to prevent background activities
            // Only add restrictions that won't break Xiaomi system functionality
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val safeRestrictions = listOf(
                    "no_install_apps",          // Prevent app installations
                    "no_uninstall_apps"         // Prevent app uninstalls
                    // NOTE: Removed other restrictions as they may interfere with MIUI
                    // Xiaomi's MIUI has many system services that need access to:
                    // - Accounts (MIUI cloud, etc.)
                    // - Location (Find Device, etc.)
                    // - Bluetooth/WiFi (system optimization)
                )

                for (restriction in safeRestrictions) {
                    try {
                        devicePolicyManager.addUserRestriction(adminComponent, restriction)
                        Log.d(TAG, "Added restriction: $restriction")
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not add restriction $restriction: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "Applied XIAOMI-SAFE power restrictions successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying power restrictions", e)
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
