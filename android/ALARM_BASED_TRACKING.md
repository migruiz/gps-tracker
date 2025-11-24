# Alarm-Based GPS Tracking Implementation

## Overview
The GPS tracking service has been refactored to use an alarm-based approach instead of continuous tracking. This significantly reduces battery consumption by keeping the app completely dormant between location updates.

## Key Changes

### 1. **Alarm-Based Architecture**
- **LocationAlarmReceiver**: New BroadcastReceiver that triggers when alarms fire
- **AlarmScheduler**: Manages scheduling of precise alarms using AlarmManager
- Service only wakes up when alarm fires, processes location, then goes back to sleep

### 2. **Location Updates**
- Uses `requestSingleLocation()` instead of continuous location tracking
- When alarm fires:
  1. Check if current time is in active window
  2. Connect to GPS and HTTP client
  3. Get single location reading (30 second timeout)
  4. Send location to API
  5. Disconnect everything
  6. Schedule next alarm

### 3. **Battery Monitoring**
- **Removed**: Hourly battery check alarms
- **New approach**: Battery info sent only with the last location update before entering IDLE state
- This reduces unnecessary wake-ups from 24 per day to just a few (end of each active period)

### 4. **State Management**
- **IDLE**: App is completely dormant, only alarm is scheduled
- **AWAKE**: Processing a location update (brief period)
- **Removed**: BATTERY_CHECK state (no longer needed)

### 5. **Alarm Scheduling Logic**
- If in active period: Schedule alarm for next minute
- If next minute is still in active period: Continue minute-by-minute alarms
- If next minute falls outside active period: Schedule alarm for start of next active period (e.g., 5 hours later)
- Uses `setExactAndAllowWhileIdle()` to work even in Doze mode

### 6. **Power Optimization**
Between alarms (during IDLE):
- No GPS tracking
- No HTTP connections
- No battery monitoring
- Only VPN remains active (as required)
- WakeLock is partial and minimal

## Files Modified

### New Files
1. `LocationAlarmReceiver.kt` - Handles alarm broadcasts
2. `AlarmScheduler.kt` - Manages alarm scheduling logic

### Modified Files
1. `GpsTrackingService.kt` - Refactored to alarm-based approach
2. `LocationManager.kt` - Added `requestSingleLocation()` method
3. `AppState.kt` - Removed BATTERY_CHECK state
4. `AndroidManifest.xml` - Added LocationAlarmReceiver registration

### Configuration
- `AppConfig.kt` already defines active time slots (AWAKE_TIME_SLOTS)
- Battery threshold for warnings: 30%

## How It Works

### Active Period (e.g., 8:00-11:00)
```
08:00 - Alarm fires → Get location → Send → Schedule alarm for 08:01
08:01 - Alarm fires → Get location → Send → Schedule alarm for 08:02
...
10:59 - Alarm fires → Get location → Send battery info → Schedule alarm for 12:00 (next period)
```

### Between Active Periods
- App is completely dormant
- Only the scheduled alarm exists
- VPN continues running

## Benefits

1. **Massive battery savings**: App only active for ~30-60 seconds per minute during active periods
2. **Reduced network usage**: HTTP client only connects when needed
3. **Doze mode compatible**: Uses `setExactAndAllowWhileIdle()` for reliable alarms
4. **Simpler state management**: Only 2 states instead of 3
5. **Less frequent battery updates**: Only sent at end of active periods instead of hourly

## Permissions Required

Already present in AndroidManifest.xml:
- `SCHEDULE_EXACT_ALARM` - For precise alarm scheduling
- `WAKE_LOCK` - For partial wake lock during location processing
- `ACCESS_FINE_LOCATION` - For GPS access
- `ACCESS_BACKGROUND_LOCATION` - For alarms while app in background

## Testing Recommendations

1. Test alarm firing during active periods
2. Test transition from active to idle state
3. Test alarm scheduling across day boundaries
4. Test battery info sent at end of active period
5. Test behavior in Doze mode
6. Verify VPN stays active throughout

## Notes

- The service still runs as a foreground service for system priority
- WakeLock is maintained but is partial (minimal power usage)
- HTTP client connection is very brief (1-2 seconds per update)
- Location timeout is 30 seconds max

