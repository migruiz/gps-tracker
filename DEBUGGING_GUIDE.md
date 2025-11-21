# GPS Tracker - Debugging Guide for Device Owner Mode

## Overview
This guide explains how to debug the GPS Tracker app both before and after setting it as Device Owner.

## Table of Contents
1. [Debugging Before Device Owner Setup](#debugging-before-device-owner-setup)
2. [Debugging After Device Owner Setup](#debugging-after-device-owner-setup)
3. [Using ADB for Debugging](#using-adb-for-debugging)
4. [Viewing Logs](#viewing-logs)
5. [Testing Without Device Owner](#testing-without-device-owner)
6. [Common Debugging Scenarios](#common-debugging-scenarios)

---

## Debugging Before Device Owner Setup

### Option 1: Normal Installation and Debugging

Before setting up as Device Owner, you can develop and debug normally:

```bash
# Install the app
.\gradlew.bat installDebug

# Or use Android Studio's "Run" button (Shift+F10)
```

**What works without Device Owner:**
- ✅ GPS tracking
- ✅ MQTT connection
- ✅ Battery monitoring
- ✅ UI testing
- ✅ Location updates
- ❌ Mobile data control
- ❌ Airplane mode control
- ❌ Kiosk mode
- ❌ Background data restrictions

### Option 2: Mock Device Owner Mode

You can add a debug flag to simulate Device Owner status for UI testing:

Edit `AppConfig.kt`:
```kotlin
object AppConfig {
    // Debug flag - set to true for testing without Device Owner
    const val DEBUG_MODE = true
    const val MOCK_DEVICE_OWNER = true // Simulates Device Owner for UI
    // ... rest of config
}
```

---

## Debugging After Device Owner Setup

### Yes, You CAN Debug in Device Owner Mode!

Device Owner mode does NOT prevent debugging. You can still:
- ✅ Use Android Studio debugger
- ✅ View logcat
- ✅ Install debug builds
- ✅ Use ADB commands
- ✅ Profile the app

### Step-by-Step: Debug While Device Owner

#### 1. Set Up Device Owner First
```bash
# Factory reset device (IMPORTANT)
# Remove all accounts
# Enable USB debugging

# Install the app
adb install app\build\outputs\apk\debug\app-debug.apk

# Set as Device Owner
adb shell dpm set-device-owner ovh.tenjo.gpstracker/.admin.DeviceAdminReceiver

# Verify
adb shell dumpsys device_policy | findstr "Device Owner"
```

#### 2. Keep USB Debugging Enabled
**IMPORTANT**: When you set Device Owner, ensure USB debugging stays enabled:

```bash
# Grant WRITE_SECURE_SETTINGS permission (if needed)
adb shell pm grant ovh.tenjo.gpstracker android.permission.WRITE_SECURE_SETTINGS

# Verify USB debugging is still enabled
adb shell settings get global adb_enabled
# Should return: 1
```

#### 3. Debug in Android Studio

Once Device Owner is set, you can still debug normally:

1. **Connect device via USB**
2. **Open project in Android Studio**
3. **Click "Run" or "Debug"** (Shift+F10 or Shift+F9)
4. **Set breakpoints** as usual
5. **View logcat** in the Logcat panel

Android Studio will:
- Install the updated APK
- Attach the debugger
- Show logs in real-time

#### 4. Reinstalling During Development

You can reinstall/update the app while maintaining Device Owner status:

```bash
# Method 1: Android Studio Run/Debug (recommended)
# Just click Run - it handles everything

# Method 2: Manual reinstall via ADB
adb install -r app\build\outputs\apk\debug\app-debug.apk

# The -r flag replaces the app without removing Device Owner status
```

**IMPORTANT**: Device Owner status persists across app reinstalls as long as you use the `-r` (replace) flag or Android Studio's Run.

---

## Using ADB for Debugging

### Essential ADB Commands

#### View Live Logs
```bash
# All app logs
adb logcat -s GPSTracker:* GpsTrackingService:* MqttManager:* LocationManager:* ConnectivityManager:* BatteryMonitor:*

# Just errors
adb logcat *:E

# Clear and start fresh
adb logcat -c
adb logcat

# Save logs to file
adb logcat -d > gps_tracker_logs.txt
```

#### Check App Status
```bash
# Is app running?
adb shell ps | findstr gpstracker

# App info
adb shell dumpsys package ovh.tenjo.gpstracker

# Device Owner status
adb shell dumpsys device_policy | findstr "Device Owner"

# Current activity
adb shell dumpsys activity | findstr "mResumedActivity"
```

#### Test State Transitions
```bash
# Force stop and restart
adb shell am force-stop ovh.tenjo.gpstracker
adb shell am start -n ovh.tenjo.gpstracker/.MainActivity

# Check service status
adb shell dumpsys activity services ovh.tenjo.gpstracker
```

#### Network Status
```bash
# Check airplane mode
adb shell settings get global airplane_mode_on

# Check mobile data
adb shell settings get global mobile_data

# Check WiFi
adb shell dumpsys wifi | findstr "mWifiEnabled"
```

#### Battery Info
```bash
# Battery level
adb shell dumpsys battery | findstr level

# Battery status
adb shell dumpsys battery
```

#### Simulate Battery Events
```bash
# Set battery level for testing
adb shell dumpsys battery set level 15

# Set charging state
adb shell dumpsys battery set ac 1  # Charging
adb shell dumpsys battery set ac 0  # Not charging

# Reset to actual values
adb shell dumpsys battery reset
```

---

## Viewing Logs

### In Android Studio

1. Open **Logcat** panel (bottom of screen)
2. Select your device
3. Filter by package: `ovh.tenjo.gpstracker`
4. Or filter by tag: `GPS*`, `Mqtt*`, `Location*`

### Using Terminal

```bash
# Filtered by package
adb logcat --pid=$(adb shell pidof -s ovh.tenjo.gpstracker)

# Filtered by tags
adb logcat GPSTracker:D MqttManager:D LocationManager:D ConnectivityManager:D *:S

# With timestamp
adb logcat -v time

# With thread info
adb logcat -v threadtime
```

### Log Levels in the App

The app uses these log levels:
- `Log.d()` - Debug info (state transitions, normal operations)
- `Log.i()` - Info (important events)
- `Log.w()` - Warnings (retryable errors, missing permissions)
- `Log.e()` - Errors (failures, exceptions)

---

## Testing Without Device Owner

For faster iteration during development, you can test most features without Device Owner:

### Create a Debug Build Variant

Edit `app/build.gradle.kts`:

```kotlin
android {
    // ...existing code...
    
    buildTypes {
        debug {
            buildConfigField("Boolean", "MOCK_DEVICE_OWNER", "true")
            buildConfigField("Boolean", "SKIP_CONNECTIVITY_CONTROL", "true")
        }
        release {
            buildConfigField("Boolean", "MOCK_DEVICE_OWNER", "false")
            buildConfigField("Boolean", "SKIP_CONNECTIVITY_CONTROL", "false")
            // ...existing release config...
        }
    }
}
```

### Add Debug Checks

The app already handles missing Device Owner gracefully:
- Logs warnings instead of crashing
- UI shows Device Owner status
- Connectivity controls fail safely

---

## Common Debugging Scenarios

### Scenario 1: MQTT Won't Connect

**Debug Steps:**
```bash
# Check network connectivity
adb shell ping -c 4 8.8.8.8

# View MQTT logs
adb logcat -s MqttManager:*

# Test MQTT broker from computer
mosquitto_sub -h your-broker.com -p 8883 -t "test" -u user -P pass --cafile ca.crt
```

**Common Issues:**
- Wrong broker URL in `AppConfig.kt`
- Invalid credentials
- Firewall blocking WebSocket port
- Certificate issues with WSS

### Scenario 2: GPS Not Working

**Debug Steps:**
```bash
# Check location permissions
adb shell dumpsys package ovh.tenjo.gpstracker | findstr permission

# Check location services
adb shell settings get secure location_providers_allowed

# View location logs
adb logcat -s LocationManager:*

# Test GPS with mock location
adb shell settings put secure mock_location 1
```

**Common Issues:**
- Location permissions not granted
- Location services disabled
- Device indoors (no GPS signal)
- GPS hardware issue

### Scenario 3: State Not Changing

**Debug Steps:**
```bash
# Check current time vs configured slots
adb shell date

# View service logs
adb logcat -s GpsTrackingService:*

# Check if service is running
adb shell dumpsys activity services | findstr GpsTrackingService
```

**Common Issues:**
- Time slots configured incorrectly
- Service not started
- Device time incorrect
- Handler not scheduled

### Scenario 4: Kiosk Mode Issues

**Debug Steps:**
```bash
# Check if lock task is enabled
adb shell dumpsys activity | findstr "lockTaskMode"

# Check status bar state
adb shell dumpsys statusbar | findstr "disable"

# Exit kiosk mode for debugging
adb shell am task lock stop
```

**To re-enter kiosk mode:**
```bash
adb shell am start -n ovh.tenjo.gpstracker/.MainActivity
```

### Scenario 5: Airplane Mode Not Working

**Debug Steps:**
```bash
# Check Device Owner status
adb shell dumpsys device_policy

# Check WRITE_SECURE_SETTINGS permission
adb shell dumpsys package ovh.tenjo.gpstracker | findstr "WRITE_SECURE_SETTINGS"

# Try setting airplane mode manually
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
```

---

## Remote Debugging Over WiFi

Once Device Owner is set, you can debug over WiFi (no USB needed):

### Setup WiFi Debugging

```bash
# Connect device via USB first
# Enable TCP/IP mode on port 5555
adb tcpip 5555

# Find device IP (on device: Settings > About > Status > IP address)
# Or via ADB:
adb shell ip addr show wlan0 | findstr "inet "

# Disconnect USB cable

# Connect over WiFi
adb connect <device-ip>:5555

# Verify connection
adb devices

# Now you can debug wirelessly!
```

### Disable WiFi Debugging
```bash
# Reconnect USB
adb usb
```

---

## Debugging Checklist

Before reporting issues, verify:

- [ ] USB debugging is enabled
- [ ] Device is connected (`adb devices`)
- [ ] App is installed (`adb shell pm list packages | findstr gpstracker`)
- [ ] Permissions are granted (check Device Owner status in UI)
- [ ] MQTT config is correct in `AppConfig.kt`
- [ ] Time slots are configured correctly
- [ ] Device time is correct
- [ ] Logs show no critical errors
- [ ] Network connectivity is available
- [ ] Location services are enabled

---

## Advanced Debugging

### Enable Verbose Logging

Add this to enable more detailed logs:

```kotlin
// In AppConfig.kt
const val DEBUG_VERBOSE = true
```

Then in your managers:
```kotlin
if (AppConfig.DEBUG_VERBOSE) {
    Log.v(TAG, "Detailed debug info here")
}
```

### Add Crash Reporting

For production, consider adding crash reporting:

```kotlin
// Add to build.gradle.kts
implementation("com.google.firebase:firebase-crashlytics:18.6.0")
```

### Monitor with ADB Shell Commands

```bash
# Watch app memory usage
adb shell top -n 1 | findstr gpstracker

# Watch battery drain
adb shell dumpsys batterystats ovh.tenjo.gpstracker

# Watch network usage
adb shell dumpsys netstats | findstr gpstracker
```

---

## Removing Device Owner for Testing

If you need to remove Device Owner to test something:

```bash
# Remove Device Owner status
adb shell dpm remove-active-admin ovh.tenjo.gpstracker/.admin.DeviceAdminReceiver

# Or factory reset (complete wipe)
adb shell am broadcast -a android.intent.action.FACTORY_RESET
```

**WARNING**: Removing Device Owner disables all privileged features!

---

## Quick Reference: Common Commands

```bash
# Build and install
.\gradlew.bat installDebug

# View logs
adb logcat -s GpsTrackingService:*

# Restart app
adb shell am force-stop ovh.tenjo.gpstracker
adb shell am start -n ovh.tenjo.gpstracker/.MainActivity

# Check Device Owner
adb shell dumpsys device_policy | findstr "Device Owner"

# Check permissions
adb shell dumpsys package ovh.tenjo.gpstracker | findstr permission

# Battery test
adb shell dumpsys battery set level 15

# Exit kiosk mode
adb shell am task lock stop

# Save logs
adb logcat -d > debug_logs.txt
```

---

## Tips for Efficient Debugging

1. **Keep USB debugging enabled** - Critical for remote troubleshooting
2. **Use filtered logcat** - Don't get overwhelmed with system logs
3. **Test incrementally** - Test GPS, MQTT, and connectivity separately
4. **Use the debug UI** - The app shows real-time status
5. **Enable Developer Options** - Shows visual feedback for debugging
6. **Test without Device Owner first** - Verify basic functionality
7. **Keep ADB handy** - Even in kiosk mode, ADB always works
8. **Save logs regularly** - Helps identify patterns in issues
9. **Test with mock data** - Use mock locations and battery levels
10. **Use WiFi debugging** - Frees you from USB cable

---

## Conclusion

**Key Takeaway**: Device Owner mode does NOT prevent debugging! You can:
- ✅ Use Android Studio normally
- ✅ Install debug builds
- ✅ View logs
- ✅ Set breakpoints
- ✅ Use ADB commands

The main difference is that you need to factory reset before setting Device Owner initially, but after that, development continues normally.

