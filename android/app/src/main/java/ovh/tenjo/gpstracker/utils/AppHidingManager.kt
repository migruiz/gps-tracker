package ovh.tenjo.gpstracker.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import ovh.tenjo.gpstracker.admin.DeviceAdminReceiver

class AppHidingManager(private val context: Context) {

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(context, DeviceAdminReceiver::class.java)
    }

    /**
     * Critical system packages that should never be hidden
     */
    private val criticalPackages = setOf(
        // Core Android system
        "android",
        "com.android.phone",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",

        // Core telephony and connectivity
        "com.android.server.telecom",
        "com.android.stk",
        "com.android.cellbroadcastreceiver",

        // Core providers
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.providers.calendar",

        // Shell and debugging
        "com.android.shell",
        "com.android.adb",

        // Input methods (at least one should remain)
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",

        // Package installer (needed for updates)
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",

        // Our own app
        context.packageName
    )

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Hide all non-critical apps to minimize CPU usage and battery drain.
     * Hidden apps cannot run, sync, or trigger alarms.
     */
    fun hideNonCriticalApps(): HideResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot hide apps - not device owner")
            return HideResult(0, 0, "Not device owner")
        }

        var hiddenCount = 0
        var failedCount = 0
        val startTime = System.currentTimeMillis()

        try {
            // Get all installed applications
            @Suppress("DEPRECATION")
            val packages = context.packageManager.getInstalledApplications(0)

            Log.d(TAG, "Found ${packages.size} installed packages")

            for (appInfo in packages) {
                val packageName = appInfo.packageName

                // Skip critical packages
                if (isCriticalPackage(packageName, appInfo)) {
                    Log.d(TAG, "Skipping critical package: $packageName")
                    continue
                }

                // Try to hide the app
                try {
                    val isHidden = devicePolicyManager.isApplicationHidden(adminComponent, packageName)

                    if (!isHidden) {
                        val success = devicePolicyManager.setApplicationHidden(
                            adminComponent,
                            packageName,
                            true
                        )

                        if (success) {
                            hiddenCount++
                            Log.d(TAG, "Hidden app: $packageName")
                        } else {
                            failedCount++
                            Log.w(TAG, "Failed to hide app: $packageName")
                        }
                    } else {
                        Log.d(TAG, "App already hidden: $packageName")
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Error hiding app: $packageName", e)
                }
            }

            val duration = System.currentTimeMillis() - startTime
            val message = "Hidden $hiddenCount apps, failed $failedCount, took ${duration}ms"
            Log.i(TAG, message)

            return HideResult(hiddenCount, failedCount, message)

        } catch (e: Exception) {
            Log.e(TAG, "Error in hideNonCriticalApps", e)
            return HideResult(hiddenCount, failedCount, "Error: ${e.message}")
        }
    }

    /**
     * Unhide all previously hidden apps (for testing or recovery)
     */
    fun unhideAllApps(): HideResult {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot unhide apps - not device owner")
            return HideResult(0, 0, "Not device owner")
        }

        var unhiddenCount = 0
        var failedCount = 0

        try {
            @Suppress("DEPRECATION")
            val packages = context.packageManager.getInstalledApplications(0)

            for (appInfo in packages) {
                val packageName = appInfo.packageName

                try {
                    val isHidden = devicePolicyManager.isApplicationHidden(adminComponent, packageName)

                    if (isHidden) {
                        val success = devicePolicyManager.setApplicationHidden(
                            adminComponent,
                            packageName,
                            false
                        )

                        if (success) {
                            unhiddenCount++
                            Log.d(TAG, "Unhidden app: $packageName")
                        } else {
                            failedCount++
                            Log.w(TAG, "Failed to unhide app: $packageName")
                        }
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Error unhiding app: $packageName", e)
                }
            }

            val message = "Unhidden $unhiddenCount apps, failed $failedCount"
            Log.i(TAG, message)

            return HideResult(unhiddenCount, failedCount, message)

        } catch (e: Exception) {
            Log.e(TAG, "Error in unhideAllApps", e)
            return HideResult(unhiddenCount, failedCount, "Error: ${e.message}")
        }
    }

    /**
     * Check if a package is critical and should not be hidden
     */
    private fun isCriticalPackage(packageName: String, appInfo: ApplicationInfo): Boolean {
        // Check if in critical list
        if (criticalPackages.contains(packageName)) {
            return true
        }

        // Check if package starts with critical prefixes
        if (packageName.startsWith("android.") ||
            packageName.startsWith("com.android.") &&
            (packageName.contains("system") ||
             packageName.contains("phone") ||
             packageName.contains("bluetooth") ||
             packageName.contains("nfc") ||
             packageName.contains("server"))) {
            return true
        }

        // Keep system apps that are part of the core system
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
            // System app - be more selective
            // Allow hiding of pre-installed bloatware but keep critical system services
            if (packageName.startsWith("com.google.") ||
                packageName.startsWith("com.samsung.") ||
                packageName.startsWith("com.huawei.") ||
                packageName.startsWith("com.xiaomi.")) {
                // These are typically bloatware, can be hidden
                return false
            }
        }

        return false
    }

    /**
     * Get count of currently hidden apps
     */
    fun getHiddenAppsCount(): Int {
        if (!isDeviceOwner()) {
            return -1
        }

        var count = 0
        try {
            @Suppress("DEPRECATION")
            val packages = context.packageManager.getInstalledApplications(0)

            for (appInfo in packages) {
                try {
                    if (devicePolicyManager.isApplicationHidden(adminComponent, appInfo.packageName)) {
                        count++
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hidden apps count", e)
        }

        return count
    }

    data class HideResult(
        val successCount: Int,
        val failedCount: Int,
        val message: String
    )

    companion object {
        private const val TAG = "AppHidingManager"
    }
}

