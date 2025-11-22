# Device Owner Mode - Call & Volume Control Features

## Overview
This document describes the call management and volume control features implemented for Device Owner (DO) mode in the GPS Tracker app.

## Features Implemented

### 1. Volume Control in Device Owner Mode

When the app is running as Device Owner, all device volumes are automatically muted:

- **Media Volume**: Muted (0%)
- **Ringtone Volume**: Muted (0%)
- **Notification Volume**: Muted (0%)
- **Alarm Volume**: Muted (0%)
- **System Volume**: Muted (0%)

**Hardware Volume Buttons**: Disabled and non-functional in DO mode using `DevicePolicyManager.setMasterVolumeMuted()`.

### 2. Call Whitelisting

Only calls from two pre-configured numbers (Mom and Dad) are allowed:

- **Incoming calls from Mom or Dad**: 
  - Phone will ring (volume temporarily restored)
  - Call can be answered normally
  - After call ends, volume is muted again

- **Incoming calls from other numbers**:
  - Automatically blocked/rejected
  - No ringing
  - Call is ended within 500ms of detection

### 3. Emergency Call Buttons

The app UI includes two prominent buttons:

- **Call Mom Button**: Initiates a call to Mom's number
- **Call Dad Button**: Initiates a call to Dad's number

These buttons are always visible and functional, allowing the device user to make emergency calls.

## Configuration

### Setting Up Phone Numbers

Edit the phone numbers in `app/src/main/java/ovh/tenjo/gpstracker/config/AppConfig.kt`:

```kotlin
// Phone Configuration - Whitelisted contacts
const val MOM_PHONE_NUMBER = "+1234567890"  // Replace with Mom's actual number
const val DAD_PHONE_NUMBER = "+0987654321"  // Replace with Dad's actual number
```

**Important**: Use the full international format with country code (e.g., `+1` for US/Canada, `+44` for UK, etc.)

## Technical Implementation

### Components Created

1. **VolumeControlManager** (`utils/VolumeControlManager.kt`)
   - Manages all volume control operations
   - Mutes/unmutes system volumes
   - Disables hardware volume buttons
   - Temporarily enables ringer for whitelisted calls

2. **CallManager** (`utils/CallManager.kt`)
   - Handles phone call operations
   - Checks if incoming numbers are whitelisted
   - Initiates calls to Mom/Dad
   - Ends/blocks unwanted calls

3. **PhoneCallReceiver** (`receiver/PhoneCallReceiver.kt`)
   - BroadcastReceiver that monitors phone state changes
   - Detects incoming calls
   - Automatically blocks non-whitelisted numbers
   - Manages ringer state based on caller

### Permissions Required

The following permissions are automatically requested by the app:

- `READ_PHONE_STATE`: To detect incoming calls
- `CALL_PHONE`: To initiate outgoing calls
- `ANSWER_PHONE_CALLS`: To programmatically answer/end calls (Android 8.0+)
- `READ_CALL_LOG`: To access call information
- `MANAGE_OWN_CALLS`: For call management

### Device Owner Capabilities Used

- `DevicePolicyManager.setMasterVolumeMuted()`: Disables volume buttons
- Device Owner status enables automatic call blocking
- Full control over audio settings without user intervention

## How It Works

### Incoming Call Flow

1. **Phone State Changes**: `PhoneCallReceiver` detects incoming call
2. **Number Check**: Extracts caller's phone number
3. **Whitelist Verification**: Compares with Mom/Dad numbers
4. **Decision**:
   - **If whitelisted**: Enable ringer, allow call to proceed
   - **If not whitelisted**: Immediately end the call (no ring)
5. **Call End**: Restore muted state after call completes

### Phone Number Matching

The app uses intelligent phone number matching:
- Strips spaces, dashes, parentheses, and plus signs
- Compares last 10 digits (handles country code variations)
- Case-insensitive comparison

Example matches:
- `+1-234-567-8900` matches `(234) 567-8900`
- `+442079460958` matches `020 7946 0958`

## User Interface

The Emergency Calls card appears in the main UI showing:
- Two large call buttons (one for Mom, one for Dad)
- Phone emoji icons for easy identification
- Configured phone numbers displayed
- Clear message: "Only calls from Mom or Dad are allowed"

## Testing

### Before Device Owner Setup

Without Device Owner status:
- Volume controls work normally
- All calls ring through
- Call buttons function but volume blocking won't work

### After Device Owner Setup

With Device Owner status:
- All volumes muted on app start
- Volume buttons have no effect
- Only whitelisted calls ring
- Non-whitelisted calls are blocked automatically

## Important Notes

1. **Device Owner Required**: Full functionality requires the app to be set as Device Owner. Follow the setup guide in `DEVICE_OWNER_SETUP.md`.

2. **Phone Numbers Must Be Updated**: The default placeholder numbers MUST be replaced with actual numbers before deployment.

3. **Emergency Services**: This app does NOT block emergency services (911, 112, etc.) - these always work regardless of whitelist.

4. **Call Log**: Blocked calls will still appear in the system call log as "Missed Calls" but won't disturb the user.

5. **International Numbers**: Always use international format with country code for reliable matching.

## Troubleshooting

### Calls Not Being Blocked
- Verify app is set as Device Owner
- Check logcat for "PhoneCallReceiver" messages
- Ensure `READ_PHONE_STATE` permission is granted

### Volume Buttons Still Work
- Verify Device Owner status
- Check `VolumeControlManager` logs
- May need to reboot device after DO setup

### Whitelisted Calls Not Ringing
- Check phone numbers are correctly formatted in `AppConfig.kt`
- Verify numbers match exactly (check country codes)
- Review logs for "enableRingerForCall" messages

### Call Buttons Don't Work
- Ensure `CALL_PHONE` permission is granted
- Check phone numbers are valid
- Review logs for error messages

## Security Considerations

- **Volume control prevents alerts**: No sound/vibration from unwanted apps
- **Call blocking prevents social engineering**: Only trusted contacts can reach the device
- **Emergency access maintained**: Call buttons provide quick access to Mom/Dad
- **Device Owner enforcement**: Cannot be bypassed without factory reset

## Future Enhancements

Possible improvements:
- Add more whitelisted numbers
- SMS filtering with same whitelist
- Call duration limits
- Scheduled quiet hours
- Remote whitelist updates via MQTT

## Related Files

- `MainActivity.kt`: Initializes managers and UI
- `AppConfig.kt`: Configuration including phone numbers
- `AndroidManifest.xml`: Permissions and receiver registration
- `PhoneCallReceiver.kt`: Call state monitoring
- `VolumeControlManager.kt`: Volume control implementation
- `CallManager.kt`: Call operations implementation

