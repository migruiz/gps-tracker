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
     * MINIMAL APPROACH - Only hide Google apps, keep everything else
     */
    private val criticalPackages = setOf(
        // Our own app
        context.packageName
    )

    /**
     * Google apps and services that are SAFE to hide
     * These are the ONLY apps that will be hidden
     */
    private val googleAppsToHide = setOf(
        // Google Play Services and Core
        "com.google.android.gms",
        "com.google.android.gsf",

        // Google Play Store
        "com.android.vending",

        // Google Apps
        "com.google.android.googlequicksearchbox",  // Google Search
        "com.google.android.apps.maps",             // Google Maps
        "com.google.android.youtube",               // YouTube
        "com.google.android.gm",                    // Gmail
        "com.google.android.talk",                  // Hangouts
        "com.google.android.apps.photos",           // Google Photos
        "com.google.android.music",                 // Google Music
        "com.google.android.videos",                // Google Play Movies
        "com.google.android.apps.docs",             // Google Docs
        "com.google.android.keep",                  // Google Keep
        "com.google.android.calendar",              // Google Calendar
        "com.google.android.apps.messaging",        // Google Messages
        "com.google.android.contacts",              // Google Contacts
        "com.google.android.dialer",                // Google Dialer
        "com.android.chrome",                       // Chrome Browser

        // Google Services that sync in background
        "com.google.android.syncadapters.contacts", // Contact sync
        "com.google.android.syncadapters.calendar", // Calendar sync
        "com.google.android.backuptransport",       // Backup service
        "com.google.android.configupdater",         // Config updater
        "com.google.android.onetimeinitializer",    // One-time initializer
        "com.google.android.partnersetup",          // Partner setup
        "com.google.android.setupwizard",           // Setup wizard
        "com.google.android.feedback",              // Feedback
        "com.google.android.printservice.recommendation" // Print service
    )

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * MINIMAL HIDING - Only hide Google apps and services
     * Keep EVERYTHING else to prevent system instability
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

            Log.d(TAG, "Found ${packages.size} installed packages - MINIMAL hiding mode (Google apps only)")

            for (appInfo in packages) {
                val packageName = appInfo.packageName

                // Skip our own app
                if (packageName == context.packageName) {
                    Log.d(TAG, "Skipping our app: $packageName")
                    continue
                }

                // Only hide if it's a Google app
                if (!googleAppsToHide.contains(packageName)) {
                    continue
                }

                // Try to hide the Google app
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
                            Log.d(TAG, "Hidden Google app: $packageName")

                            // Also try to suspend the package (API 24+)
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                    devicePolicyManager.setPackagesSuspended(
                                        adminComponent,
                                        arrayOf(packageName),
                                        true
                                    )
                                }
                            } catch (e: Exception) {
                                // Some packages cannot be suspended
                                Log.d(TAG, "Could not suspend $packageName: ${e.message}")
                            }
                        } else {
                            failedCount++
                            Log.w(TAG, "Failed to hide Google app: $packageName")
                        }
                    } else {
                        Log.d(TAG, "Google app already hidden: $packageName")
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Error hiding Google app: $packageName", e)
                }
            }

            val duration = System.currentTimeMillis() - startTime
            val message = "MINIMAL MODE: Hidden $hiddenCount Google apps, failed $failedCount, took ${duration}ms"
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
