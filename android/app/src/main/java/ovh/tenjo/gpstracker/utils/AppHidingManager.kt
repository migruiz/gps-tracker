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
     * OPTIMIZED FOR XIAOMI REDMI NOTE 2 - ANDROID 10
     */
    private val criticalPackages = setOf(
        // Core Android system (absolutely required)
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",

        // Phone/Telephony (for calls to 2 numbers)
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.cellbroadcastreceiver",

        // Location services (for GPS)
        "com.android.location.fused",
        "com.android.location",

        // Minimal providers needed
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.providers.downloads",

        // Shell (for debugging if needed)
        "com.android.shell",

        // Input method (keyboard) - at least one needed
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",

        // Package installer (needed for system stability)
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",

        // XIAOMI CRITICAL SYSTEM APPS - DO NOT HIDE
        "com.miui.home",                    // MIUI launcher - critical!
        "com.miui.systemui",                // MIUI system UI
        "com.android.incallui",             // Call UI
        "com.miui.securitycenter",          // MIUI security (handles permissions)
        "com.xiaomi.finddevice",            // Find device (system service)
        "com.miui.powerkeeper",             // Power management (critical for battery)
        "com.miui.notification",            // Notification system
        "com.android.mms",                  // SMS/MMS (for emergency)
        "com.android.contacts",             // Contacts (needed for calls)
        "com.miui.core",                    // MIUI core services
        "com.xiaomi.market",                // May be required by system
        "com.miui.analytics",               // System analytics
        "com.miui.daemon",                  // MIUI daemon
        "com.xiaomi.xmsf",                  // Xiaomi message service framework

        // Our own app
        context.packageName
    )

    /**
     * Additional system packages that are allowed to run
     * These are Android core services that cannot be hidden but are essential
     */
    private val allowedSystemPrefixes = setOf(
        "android.",
        "com.android.location",
        "com.android.server",
        "com.qualcomm.",                    // Qualcomm system services (Xiaomi uses Qualcomm chips)
        "com.qti.",                         // Qualcomm technologies
        "org.codeaurora."                   // Code Aurora (Qualcomm)
    )

    /**
     * Xiaomi bloatware that is SAFE to hide
     * These are confirmed non-critical apps that won't break the system
     */
    private val xiaomiSafeToHide = setOf(
        "com.xiaomi.gamecenter",
        "com.xiaomi.glgm",
        "com.xiaomi.payment",
        "com.xiaomi.scanner",
        "com.xiaomi.shop",
        "com.mi.android.globalminusscreen",
        "com.miui.gallery",
        "com.miui.video",
        "com.miui.player",
        "com.miui.notes",
        "com.miui.calculator",
        "com.miui.weather2",
        "com.miui.compass",
        "com.miui.fm",
        "com.android.browser",
        "com.android.chrome",
        "com.miui.bugreport",
        "com.miui.yellowpage"
    )

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * AGGRESSIVELY hide all non-critical apps to minimize CPU usage and battery drain.
     * This uses a whitelist approach - everything not explicitly needed is hidden.
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

            Log.d(TAG, "Found ${packages.size} installed packages - using AGGRESSIVE hiding mode")

            for (appInfo in packages) {
                val packageName = appInfo.packageName

                // Skip critical packages (whitelist approach)
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

                            // Also suspend the package to ensure it can't run (API 24+)
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
            val message = "AGGRESSIVE MODE: Hidden $hiddenCount apps, failed $failedCount, took ${duration}ms"
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
     * XIAOMI-SAFE APPROACH - protects critical MIUI system components
     */
    private fun isCriticalPackage(packageName: String, appInfo: ApplicationInfo): Boolean {
        // Check if in critical whitelist
        if (criticalPackages.contains(packageName)) {
            return true
        }

        // Check if package starts with critical prefixes (very limited set)
        for (prefix in allowedSystemPrefixes) {
            if (packageName.startsWith(prefix)) {
                return true
            }
        }

        // Special check for Android system processes (cannot be hidden)
        if (packageName.startsWith("com.android.") &&
            (packageName.contains("systemui") ||
             packageName.contains("phone") ||
             packageName.contains("telecom") ||
             packageName.contains("incallui") ||
             packageName.contains("mms") ||
             packageName.contains("contacts"))) {
            return true
        }

        // XIAOMI-SPECIFIC: Keep critical MIUI system components
        if (packageName.startsWith("com.miui.") || packageName.startsWith("com.xiaomi.")) {
            // Check if it's safe to hide
            if (xiaomiSafeToHide.contains(packageName)) {
                Log.d(TAG, "HIDING safe Xiaomi bloatware: $packageName")
                return false
            }

            // Keep all other MIUI/Xiaomi system apps to prevent instability
            Log.d(TAG, "KEEPING Xiaomi system app (safety): $packageName")
            return true
        }

        // AGGRESSIVE: Hide ALL Google services (Play Services, Play Store, etc)
        // They try to sync and use CPU constantly
        if (packageName.startsWith("com.google.")) {
            Log.d(TAG, "HIDING Google service: $packageName")
            return false
        }

        // AGGRESSIVE: Hide other manufacturer bloatware (not Xiaomi)
        if (packageName.startsWith("com.samsung.") ||
            packageName.startsWith("com.huawei.") ||
            packageName.startsWith("com.oppo.") ||
            packageName.startsWith("com.vivo.") ||
            packageName.startsWith("com.oneplus.")) {
            Log.d(TAG, "HIDING other manufacturer bloatware: $packageName")
            return false
        }

        // CONSERVATIVE: For system apps not in whitelist, be cautious
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
            // It's a system app - hide only if we're sure it's safe
            // Keep most system apps to prevent instability on Xiaomi
            Log.d(TAG, "KEEPING system app (conservative): $packageName")
            return true
        }

        // AGGRESSIVE: Hide ALL user-installed apps (they're not in our whitelist)
        Log.d(TAG, "HIDING user-installed app: $packageName")
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
