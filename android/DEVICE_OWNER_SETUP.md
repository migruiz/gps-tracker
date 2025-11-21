# GPS Tracker - Device Owner Setup Guide

## Overview
This guide provides step-by-step instructions to set up your Android device as a dedicated GPS tracker in kiosk mode using Device Owner privileges.

## Prerequisites
- Android device (minimum Android 5.1, API 22)
- Computer with ADB (Android Debug Bridge) installed
- USB cable
- The GPS Tracker APK built and ready to install

## Part 1: Initial Device Preparation

### Step 1: Factory Reset the Device
**IMPORTANT**: The device MUST be factory reset and have NO Google accounts added.

1. Go to **Settings** > **System** > **Reset options** > **Erase all data (factory reset)**
2. Confirm and wait for the device to reset
3. **DO NOT** complete the initial setup wizard yet
4. **DO NOT** add any Google account

### Step 2: Enable Developer Options
1. Complete the basic setup wizard (skip Google account sign-in)
2. Go to **Settings** > **About phone**
3. Tap **Build number** 7 times to enable Developer Options
4. Go back to **Settings** > **System** > **Developer options**
5. Enable **USB debugging**
6. Enable **Stay awake** (optional, but recommended)

### Step 3: Install ADB on Your Computer

#### Windows:
1. Download Android SDK Platform Tools from: https://developer.android.com/studio/releases/platform-tools
2. Extract the ZIP file to a folder (e.g., `C:\platform-tools`)
3. Add the folder to your PATH or navigate to it in Command Prompt

#### Mac/Linux:
```bash
# Mac (using Homebrew)
brew install android-platform-tools

# Linux (Ubuntu/Debian)
sudo apt-get install android-tools-adb
```

### Step 4: Verify ADB Connection
1. Connect your Android device to the computer via USB
2. On the device, allow USB debugging when prompted
3. On your computer, open a terminal/command prompt and run:
```bash
adb devices
```
4. You should see your device listed. If not, check USB debugging is enabled.

## Part 2: Installing the App

### Step 1: Build and Install the APK
```bash
# Navigate to your project directory
cd C:\repos\gps-tracker

# Build the APK (debug version)
gradlew.bat assembleDebug

# Install the APK
adb install app\build\outputs\apk\debug\app-debug.apk
```

### Step 2: Verify Installation
```bash
adb shell pm list packages | findstr gpstracker
```
You should see: `package:ovh.tenjo.gpstracker`

## Part 3: Setting Device Owner

### Step 1: Remove All Accounts
**CRITICAL**: The device must have NO accounts (Google, Samsung, etc.)

Check for accounts:
```bash
adb shell dumpsys account
```

If any accounts exist, remove them:
- Go to **Settings** > **Accounts** and remove all accounts

### Step 2: Set as Device Owner
Run this command to set the app as Device Owner:
```bash
adb shell dpm set-device-owner ovh.tenjo.gpstracker/.admin.DeviceAdminReceiver
```

**Expected output:**
```
Success: Device owner set to package ovh.tenjo.gpstracker
Active admin set to component {ovh.tenjo.gpstracker/ovh.tenjo.gpstracker.admin.DeviceAdminReceiver}
```

**If you get an error:**
- "Not allowed to set the device owner because there are already some accounts on the device"
  - Remove all accounts and try again
  
- "Trying to set the device owner, but device owner is already set"
  - Device owner is already configured (good!)
  
- "Not allowed to set the device owner because there are already several users on the device"
  - Remove secondary users from Settings > Users

### Step 3: Verify Device Owner Status
```bash
adb shell dumpsys device_policy | findstr "Device Owner"
```

You should see your app listed as the device owner.

## Part 4: Granting Additional Permissions

### Grant System Permissions
Run these commands to grant necessary system-level permissions:

```bash
# Location permissions
adb shell pm grant ovh.tenjo.gpstracker android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ovh.tenjo.gpstracker android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant ovh.tenjo.gpstracker android.permission.ACCESS_BACKGROUND_LOCATION

# Network permissions
adb shell pm grant ovh.tenjo.gpstracker android.permission.CHANGE_NETWORK_STATE
adb shell pm grant ovh.tenjo.gpstracker android.permission.CHANGE_WIFI_STATE

# System write permissions (as Device Owner, these should work)
adb shell pm grant ovh.tenjo.gpstracker android.permission.WRITE_SECURE_SETTINGS
```

### Disable Battery Optimization
```bash
adb shell dumpsys deviceidle whitelist +ovh.tenjo.gpstracker
```

## Part 5: Configure MQTT Settings

Before launching the app, you need to configure your MQTT broker details.

1. Edit the file: `app/src/main/java/ovh/tenjo/gpstracker/config/AppConfig.kt`
2. Update these values:
   ```kotlin
   const val MQTT_BROKER_URL = "wss://your-mqtt-broker.com:8883"
   const val MQTT_CLIENT_ID = "gps-tracker-device-001"
   const val MQTT_USERNAME = "your-username"
   const val MQTT_PASSWORD = "your-password"
   ```
3. Rebuild and reinstall the app

## Part 6: Configure Tracking Schedule

Edit the awake time slots in `AppConfig.kt`:

```kotlin
val AWAKE_TIME_SLOTS = listOf(
    TimeSlot(8, 0, 9, 0),     // 08:00 -> 09:00
    TimeSlot(14, 0, 15, 0),   // 14:00 -> 15:00
    TimeSlot(18, 0, 19, 0)    // 18:00 -> 19:00
)
```

## Part 7: Launch and Test

### Step 1: Launch the App
```bash
adb shell am start -n ovh.tenjo.gpstracker/.MainActivity
```

The app will automatically:
- Request and receive necessary permissions
- Start the foreground GPS tracking service
- Enter kiosk mode (lock task)
- Set itself as the home launcher

### Step 2: Verify Kiosk Mode
Try pressing the home button or recent apps button - they should not work. The app is now locked as the only accessible application.

### Step 3: Monitor Logs
```bash
adb logcat -s GPSTracker:* GpsTrackingService:* MqttManager:* LocationManager:* ConnectivityManager:*
```

## Part 8: Network Data Restrictions

As Device Owner, the app automatically restricts background data for other apps. To verify:

```bash
adb shell dumpsys devicepolicy
```

Look for application restrictions applied to other packages.

## Part 9: Testing the App

### Test State Transitions
1. Check the debug UI - it should show:
   - Current state (IDLE or AWAKE)
   - Device owner status (✓ Device Owner)
   - Battery level
   - MQTT connection status (when AWAKE)
   - GPS tracking status (when AWAKE)

2. To test AWAKE state immediately, temporarily modify the time slots in `AppConfig.kt` to match the current time.

### Test Battery Check
The app checks battery every hour. To test immediately:
- Wait for the hourly battery check
- Or modify `BATTERY_CHECK_INTERVAL_MS` to a shorter interval (e.g., 300000 for 5 minutes)

### Monitor MQTT Messages
Use an MQTT client to subscribe to your topics:
```bash
# Location updates
mosquitto_sub -h your-mqtt-broker.com -p 8883 -t "gps/location" -u your-username -P your-password --cafile ca.crt

# Battery warnings
mosquitto_sub -h your-mqtt-broker.com -p 8883 -t "gps/battery/warning" -u your-username -P your-password --cafile ca.crt
```

## Part 10: Exiting Kiosk Mode (For Development/Debugging)

If you need to exit kiosk mode:

### Method 1: Via ADB
```bash
adb shell am task lock stop
```

### Method 2: Remove Device Owner
```bash
adb shell dpm remove-active-admin ovh.tenjo.gpstracker/.admin.DeviceAdminReceiver
```

**WARNING**: This will disable all Device Owner features!

## Troubleshooting

### App doesn't start tracking
- Check if it's within an awake time slot
- Verify MQTT broker credentials are correct
- Check logcat for errors

### Cannot set Device Owner
- Ensure device is factory reset
- Remove ALL accounts (Google, Samsung, etc.)
- Make sure no other profile/user exists

### GPS not working
- Ensure location services are enabled in Settings
- Check location permissions are granted
- Verify GPS hardware is working (test in Google Maps first)

### MQTT connection fails
- Verify broker URL, port, username, and password
- Check network connectivity
- Test broker connection with a different MQTT client
- Check if broker requires SSL certificates

### Battery drains quickly even in IDLE
- Verify airplane mode is being enabled correctly
- Check logcat to ensure state transitions are working
- Reduce GPS update frequency in AppConfig

### Other apps still using data
- Verify Device Owner status is active
- Check that background data restrictions were applied
- Some system apps may bypass restrictions

## Production Deployment Checklist

- [ ] Factory reset device
- [ ] Install app and set as Device Owner
- [ ] Configure MQTT credentials
- [ ] Set correct time slots for tracking
- [ ] Test one full awake/idle cycle
- [ ] Verify battery check functionality
- [ ] Confirm MQTT messages are being received
- [ ] Disable battery optimization
- [ ] Test battery life over 24 hours
- [ ] Verify kiosk mode is active
- [ ] Test physical buttons (home, back, recent) are disabled
- [ ] Document device ID and MQTT client ID

## Advanced Configuration

### Custom Time Slots
Edit `AppConfig.kt` to add/modify time slots:
```kotlin
val AWAKE_TIME_SLOTS = listOf(
    TimeSlot(6, 30, 8, 30),    // Morning
    TimeSlot(12, 0, 13, 0),    // Lunch
    TimeSlot(17, 0, 19, 30)    // Evening
)
```

### Adjust GPS Update Frequency
```kotlin
const val GPS_UPDATE_INTERVAL_MS = 30000L    // 30 seconds (default)
const val GPS_FASTEST_INTERVAL_MS = 15000L   // 15 seconds (default)
```

### Adjust Battery Check Frequency
```kotlin
const val BATTERY_CHECK_INTERVAL_MS = 3600000L  // 1 hour (default)
const val BATTERY_LOW_THRESHOLD = 20             // 20% (default)
```

## Security Considerations

1. **MQTT Credentials**: Store in a secure config file or use Android Keystore
2. **Network Security**: Use WSS (WebSocket Secure) for MQTT
3. **Device Physical Security**: Device owner has full control - secure the device physically
4. **Remote Management**: Consider adding remote configuration/control via MQTT

## Monitoring and Maintenance

### Remote Monitoring
Subscribe to MQTT topics to monitor:
- Location updates (every 30 seconds during awake periods)
- Battery warnings (when below threshold)
- Device status

### Log Collection
```bash
# Collect logs for analysis
adb logcat -d > gps_tracker_logs.txt
```

### Over-the-Air Updates
To update the app remotely:
1. Build new APK
2. Upload to a server
3. Use ADB over network or implement in-app update mechanism

## Support

For issues or questions:
- Check logcat output for detailed error messages
- Review the debug UI for current state information
- Verify all prerequisites are met

## Summary

Your GPS tracker is now configured to:
- ✅ Automatically wake up during configured time slots
- ✅ Track GPS location and send to MQTT every 30 seconds
- ✅ Return to idle/power-saving mode outside time slots
- ✅ Check battery every hour and send warnings
- ✅ Restrict network data to only this app
- ✅ Run in kiosk mode as the only accessible app
- ✅ Optimize battery usage during idle periods

