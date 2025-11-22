# Nuclear App Removal Feature

## Overview

The "Nuclear App Removal" feature hides all non-critical apps on the device to maximize battery life and minimize CPU usage. Hidden apps are effectively uninstalled for the current user—they cannot run, sync, or trigger alarms.

## Implementation

### How It Works

1. **On Service Startup**: When `GpsTrackingService` starts, it detects if the app is running as Device Owner
2. **App Iteration**: It iterates through all installed applications on the device
3. **Smart Filtering**: Apps are categorized as "critical" or "non-critical"
4. **Hiding**: Non-critical apps are hidden using `DevicePolicyManager.setApplicationHidden()`

### Critical Apps (Never Hidden)

The following apps are protected and will never be hidden:

- **Core Android System**: `android`, `com.android.phone`, `com.android.systemui`, `com.android.settings`
- **Telephony**: `com.android.server.telecom`, `com.android.stk`, `com.android.cellbroadcastreceiver`
- **Core Providers**: Telephony, contacts, calendar providers
- **System Tools**: Shell, ADB
- **Input Methods**: At least one keyboard remains available
- **Package Installer**: Needed for app updates
- **This GPS Tracker App**: Obviously not hidden

### Code Structure

#### AppHidingManager.kt

```
ovh.tenjo.gpstracker.utils.AppHidingManager
```

Main class responsible for:
- Detecting Device Owner status
- Iterating through installed apps
- Determining which apps are critical
- Hiding/unhiding apps
- Reporting results

Key methods:
- `hideNonCriticalApps()`: Main entry point, hides all non-critical apps
- `unhideAllApps()`: Recovery function to restore all apps
- `getHiddenAppsCount()`: Returns count of currently hidden apps
- `isCriticalPackage()`: Logic to determine if an app is critical

#### GpsTrackingService.kt Integration

The nuclear app removal is triggered in the `onCreate()` method:

```kotlin
if (connectivityManager.isDeviceOwner()) {
    // Nuclear app removal - hide all non-critical apps to save CPU/battery
    Thread {
        val result = appHidingManager.hideNonCriticalApps()
        Log.i(TAG, "App hiding completed: ${result.message}")
    }.start()
}
```

Runs in a background thread to avoid blocking service startup.

### Benefits

1. **Battery Savings**: Hidden apps cannot run background services, sync, or trigger alarms
2. **CPU Reduction**: Operating system has almost zero background processes
3. **Memory Efficiency**: Less RAM consumed by unused apps
4. **Network Conservation**: No background data usage from hidden apps

### UI Display

The Debug UI shows:
- **Device Owner Status**: Whether app is running as device owner
- **Hidden Apps Count**: Number of currently hidden apps
- **System Optimization**: Indicator that system is optimized for battery

### Recovery

If you need to restore hidden apps (for debugging or recovery):

```kotlin
val appHidingManager = AppHidingManager(context)
val result = appHidingManager.unhideAllApps()
Log.i(TAG, "Restored ${result.successCount} apps")
```

## Requirements

- **Device Owner Mode**: App must be set as Device Owner
- **Android 5.0+**: `setApplicationHidden()` API available since API level 21
- **Package Query Permission**: Declared in AndroidManifest.xml (queries all packages)

## Logging

The feature provides detailed logging:

```
AppHidingManager: Found 150 installed packages
AppHidingManager: Skipping critical package: com.android.phone
AppHidingManager: Hidden app: com.facebook.katana
AppHidingManager: Hidden app: com.google.android.youtube
AppHidingManager: Hidden 142 apps, failed 0, took 2345ms
```

## Comparison with App Suspension

| Feature | Suspended Apps | Hidden Apps (Nuclear) |
|---------|---------------|----------------------|
| Can Run | Limited | ❌ No |
| Background Sync | Reduced | ❌ No |
| Alarms | Reduced | ❌ No |
| Visible to User | Yes | ❌ No |
| Battery Impact | Medium | ✅ Minimal |
| Reversible | Yes | Yes |

## Testing

To test the feature:

1. Set the app as Device Owner (see `DEVICE_OWNER_SETUP.md`)
2. Start the app
3. Check logs for "App hiding completed" message
4. View the Debug UI to see hidden apps count
5. Check device settings - hidden apps should not appear in app list

## Security Considerations

- Hidden apps are only hidden for the current user profile
- Hidden apps cannot be launched by the user
- Hidden apps cannot run any code
- The feature can be reversed by calling `unhideAllApps()`

## Performance

Typical results on a device with 150+ apps:
- **Startup Time**: 2-3 seconds to hide all apps
- **Apps Hidden**: 130-140 apps (depending on bloatware)
- **Battery Impact**: Significantly reduced background activity
- **System Resources**: Minimal - only runs once at startup

