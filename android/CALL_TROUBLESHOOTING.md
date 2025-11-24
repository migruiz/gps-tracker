# Troubleshooting Call & Volume Issues

## Issue 1: Incoming Calls Show as "null" Number

### Problem
The PhoneCallReceiver logs show incoming phone numbers as `null`:
```
Phone state changed: RINGING, number: null
```

### Causes
1. **Android 12+ Privacy Changes**: Android 12 (API 31) and newer restrict access to caller ID for privacy
2. **Missing Permissions**: `READ_PHONE_STATE` or `READ_CALL_LOG` not granted
3. **Caller ID Blocking**: Some carriers or the caller may block their number

### Solutions

#### Solution 1: Grant READ_CALL_LOG Permission
The app now requests `READ_CALL_LOG` which helps on some Android versions:

```bash
# Via ADB (if app is installed):
adb shell pm grant ovh.tenjo.gpstracker android.permission.READ_CALL_LOG
```

#### Solution 2: Updated Behavior (IMPLEMENTED)
The app has been updated to handle null numbers safely:
- **When caller ID is null**: The call is **ALLOWED** (to avoid blocking Mom/Dad)
- **When caller ID is available**: Normal whitelist checking applies

This is a "fail-safe" approach - better to allow an unknown call than risk blocking Mom or Dad.

#### Solution 3: For Android 12+ Devices
On Android 12+, you may need to use the Device Owner role management API. The current implementation should work but caller ID may not be available.

---

## Issue 2: Call Buttons Not Working

### Problem
Pressing "Call Mom" or "Call Dad" buttons logs the attempt but doesn't make the call:
```
Initiating call to: +353894195242
```

### Causes
1. **CALL_PHONE Permission**: Not granted at runtime
2. **Default Phone App**: Not set or conflicting with another app
3. **Device Owner Restrictions**: Some settings may interfere

### Solutions

#### Solution 1: Verify CALL_PHONE Permission (MOST COMMON)

```bash
# Check if permission is granted:
adb shell dumpsys package ovh.tenjo.gpstracker | grep CALL_PHONE

# Grant permission manually if needed:
adb shell pm grant ovh.tenjo.gpstracker android.permission.CALL_PHONE
```

#### Solution 2: Updated CallManager (IMPLEMENTED)
The CallManager has been updated with better error handling:
- Tries `ACTION_CALL` (direct call) first
- Falls back to `ACTION_DIAL` (opens dialer) if permission denied
- Logs detailed error messages

#### Solution 3: Set as Default Dialer (Device Owner Method)

If the app is Device Owner, you can set it as the default dialer app:

```bash
# Via ADB:
adb shell dpm set-profile-owner --user 0 ovh.tenjo.gpstracker/.admin.DeviceAdminReceiver

# Then set as default dialer:
adb shell cmd role add-role-holder --user 0 android.app.role.DIALER ovh.tenjo.gpstracker
```

---

## Testing Steps

### Test Incoming Calls

1. **Enable verbose logging**:
   ```bash
   adb logcat -s PhoneCallReceiver:* CallManager:* VolumeControlManager:*
   ```

2. **Test with whitelisted number**:
   - Call from Mom's number
   - Should see: "Whitelisted number detected - allowing call"
   - Phone should ring

3. **Test with non-whitelisted number**:
   - Call from any other number
   - Should see: "Non-whitelisted number detected - blocking call"
   - Call should be rejected (or allowed if caller ID is null)

### Test Outgoing Calls

1. **Check permissions**:
   ```bash
   adb shell dumpsys package ovh.tenjo.gpstracker | grep "android.permission.CALL_PHONE"
   ```
   Should show: `granted=true`

2. **Test Call Mom button**:
   - Press "Call Mom" button
   - Watch logcat for errors
   - Should either:
     - Make the call directly (if CALL_PHONE granted)
     - Open dialer with number pre-filled (if permission denied)

3. **Test Call Dad button**:
   - Same as above with Dad's number

---

## Manual Permission Grant (Device Owner)

If you're running as Device Owner, grant all permissions at once:

```bash
# Grant phone permissions
adb shell pm grant ovh.tenjo.gpstracker android.permission.READ_PHONE_STATE
adb shell pm grant ovh.tenjo.gpstracker android.permission.CALL_PHONE
adb shell pm grant ovh.tenjo.gpstracker android.permission.ANSWER_PHONE_CALLS
adb shell pm grant ovh.tenjo.gpstracker android.permission.READ_CALL_LOG

# Verify all granted
adb shell dumpsys package ovh.tenjo.gpstracker | grep "android.permission" | grep "phone\|call"
```

---

## Logcat Commands

### View All Call-Related Logs
```bash
adb logcat -s PhoneCallReceiver:D CallManager:D VolumeControlManager:D MainActivity:D
```

### View Only Errors
```bash
adb logcat -s PhoneCallReceiver:E CallManager:E VolumeControlManager:E *:S
```

### Save Logs to File
```bash
adb logcat -s PhoneCallReceiver:D CallManager:D > call_logs.txt
```

---

## Expected Behavior After Fixes

### Incoming Calls
- **From Mom/Dad** (if caller ID available):
  - ✅ Ring volume enabled temporarily
  - ✅ Call allowed through
  - ✅ Volume muted after call ends

- **From Unknown Number** (caller ID available):
  - ✅ No ringing
  - ✅ Call auto-rejected within 500ms
  - ✅ Volume stays muted

- **Caller ID Unavailable** (null number):
  - ⚠️ Call allowed (fail-safe mode)
  - ✅ Ring volume enabled
  - ✅ Volume muted after call ends

### Outgoing Calls
- **Press Call Mom/Dad buttons**:
  - ✅ Call initiated immediately
  - ✅ Falls back to dialer if permission issue
  - ✅ Detailed error logging

---

## Still Having Issues?

### Collect Debug Info

```bash
# Full permission dump
adb shell dumpsys package ovh.tenjo.gpstracker > permissions.txt

# Device Owner status
adb shell dpm list-owners

# Recent calls test
adb logcat -c  # Clear logs
# Make a test call
adb logcat -d > test_call.txt
```

### Common Fixes

1. **Reinstall app**:
   ```bash
   adb uninstall ovh.tenjo.gpstracker
   adb install -r app-debug.apk
   ```

2. **Reset permissions**:
   ```bash
   adb shell pm reset-permissions ovh.tenjo.gpstracker
   ```

3. **Reboot device**:
   ```bash
   adb reboot
   ```

---

## Changes Made to Fix Issues

### PhoneCallReceiver.kt
- Added null-safety for incoming phone numbers
- Implemented "allow when unknown" fail-safe behavior
- Added Android 12+ privacy restriction warnings
- Improved logging for debugging

### CallManager.kt
- Added SecurityException handling for CALL_PHONE
- Implemented fallback to ACTION_DIAL
- Enhanced error logging
- Better exception handling for all call operations

### MainActivity.kt
- Added READ_CALL_LOG permission request
- Made callManager public for UI access
- Improved permission request logging

### AndroidManifest.xml
- Ensured all phone-related permissions are declared
- Added READ_CALL_LOG permission

