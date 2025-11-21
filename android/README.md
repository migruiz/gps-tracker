# GPS Tracker - Kiosk Mode Application

A specialized Android GPS tracking application designed to run in kiosk mode with Device Owner privileges. The app operates on a scheduled basis, tracking GPS location during configured time slots and entering power-saving mode during idle periods.

## Features

- ✅ **Scheduled Tracking**: Automatically activates GPS tracking during configured time slots
- ✅ **Power Optimization**: Disables GPS, mobile data, and enables airplane mode during idle periods
- ✅ **MQTT Integration**: Sends location data to MQTT broker via WebSocket Secure (WSS)
- ✅ **Battery Monitoring**: Hourly battery checks with low battery warnings via MQTT
- ✅ **Kiosk Mode**: Runs as the only accessible app with Device Owner privileges
- ✅ **Network Control**: Restricts background data for all other apps to minimize data usage
- ✅ **Debug UI**: Real-time display of tracking state, MQTT connection, battery, and GPS status

## How It Works

### States

1. **IDLE State**
   - GPS disabled
   - Mobile data disabled
   - WiFi disabled
   - Airplane mode enabled
   - Maximum power savings

2. **AWAKE State**
   - Airplane mode disabled
   - Mobile data enabled
   - MQTT connected
   - GPS active, sending location every 30 seconds
   - Active during configured time slots

3. **BATTERY_CHECK State**
   - Occurs every hour
   - Temporarily enables network (WiFi preferred, then mobile data)
   - Checks battery level
   - Sends MQTT warning if battery below threshold (20% default)
   - Returns to previous state

### Default Time Slots

The app is configured to be AWAKE during:
- 08:00 - 09:00
- 14:00 - 15:00
- 18:00 - 19:00

Outside these times, it enters IDLE state for maximum battery savings.

## Prerequisites

- Android device (minimum API 22 / Android 5.1)
- Device must be factory reset with no accounts
- ADB (Android Debug Bridge) installed on computer
- MQTT broker with WebSocket support

## Quick Start

1. **Configure MQTT Settings**
   - Edit `app/src/main/java/ovh/tenjo/gpstracker/config/AppConfig.kt`
   - Update broker URL, credentials, and topics

2. **Build the App**
   ```bash
   gradlew.bat assembleDebug
   ```

3. **Follow Device Owner Setup**
   - See [DEVICE_OWNER_SETUP.md](DEVICE_OWNER_SETUP.md) for detailed instructions
   - Factory reset device
   - Install app
   - Set as Device Owner via ADB

## Configuration

### Time Slots
Edit `AppConfig.kt` to configure when the device should be awake:

```kotlin
val AWAKE_TIME_SLOTS = listOf(
    TimeSlot(8, 0, 9, 0),     // 08:00 -> 09:00
    TimeSlot(14, 0, 15, 0),   // 14:00 -> 15:00
    TimeSlot(18, 0, 19, 0)    // 18:00 -> 19:00
)
```

### MQTT Broker
```kotlin
const val MQTT_BROKER_URL = "wss://your-mqtt-broker.com:8883"
const val MQTT_CLIENT_ID = "gps-tracker-device"
const val MQTT_USERNAME = "your-username"
const val MQTT_PASSWORD = "your-password"
const val MQTT_TOPIC_LOCATION = "gps/location"
const val MQTT_TOPIC_BATTERY = "gps/battery/warning"
```

### GPS Settings
```kotlin
const val GPS_UPDATE_INTERVAL_MS = 30000L    // 30 seconds
const val GPS_FASTEST_INTERVAL_MS = 15000L   // 15 seconds
```

### Battery Settings
```kotlin
const val BATTERY_CHECK_INTERVAL_MS = 3600000L  // 1 hour
const val BATTERY_LOW_THRESHOLD = 20             // 20%
```

## MQTT Message Format

### Location Message (gps/location)
```json
{
  "latitude": 40.7128,
  "longitude": -74.0060,
  "accuracy": 15.5,
  "timestamp": 1700000000000,
  "device_id": "gps-tracker-device"
}
```

### Battery Warning Message (gps/battery/warning)
```json
{
  "battery_level": 18,
  "is_charging": false,
  "timestamp": 1700000000000,
  "device_id": "gps-tracker-device"
}
```

## Project Structure

```
app/src/main/java/ovh/tenjo/gpstracker/
├── MainActivity.kt                    # Main UI with debug display
├── admin/
│   └── DeviceAdminReceiver.kt        # Device owner receiver
├── config/
│   └── AppConfig.kt                  # All configuration settings
├── location/
│   └── LocationManager.kt            # GPS location handling
├── model/
│   ├── AppState.kt                   # App state enum
│   └── TimeSlot.kt                   # Time slot data class
├── mqtt/
│   └── MqttManager.kt                # MQTT client management
├── service/
│   └── GpsTrackingService.kt         # Foreground tracking service
└── utils/
    ├── BatteryMonitor.kt             # Battery status monitoring
    └── ConnectivityManager.kt        # Network control (Device Owner)
```

## Debug UI

The app displays a comprehensive debug interface showing:
- Current date/time
- Current state (IDLE/AWAKE/BATTERY_CHECK)
- Device Owner status
- Battery level and charging status
- MQTT connection details (when AWAKE)
- GPS tracking status (when AWAKE)
- Configured time slots
- System information

## Device Owner Privileges

The app uses Device Owner mode to:
- Enable/disable mobile data programmatically
- Enable/disable airplane mode
- Restrict background data for other apps
- Lock the device in kiosk mode (lock task)
- Disable status bar and keyguard

## Data Usage Optimization

To minimize data charges:
1. Only this app can access network during AWAKE state
2. Background data restricted for all other apps
3. Compact JSON messages via MQTT
4. Configurable update intervals
5. Network disabled during IDLE state
6. WiFi preferred over mobile data for battery checks

## Battery Optimization

The app maximizes battery life by:
1. Enabling airplane mode during IDLE (disables all radios)
2. Stopping GPS when not in AWAKE state
3. Using partial wake lock (CPU only)
4. Efficient MQTT connection management
5. Minimal UI updates
6. Scheduled operation only during required times

## Monitoring

Subscribe to MQTT topics to monitor devices:

```bash
# Monitor location updates
mosquitto_sub -h broker.com -p 8883 -t "gps/location" -u user -P pass

# Monitor battery warnings
mosquitto_sub -h broker.com -p 8883 -t "gps/battery/warning" -u user -P pass
```

## Troubleshooting

See [DEVICE_OWNER_SETUP.md](DEVICE_OWNER_SETUP.md) for detailed troubleshooting steps.

Common issues:
- **Cannot set Device Owner**: Device must be factory reset with no accounts
- **GPS not working**: Check location permissions and services
- **MQTT fails**: Verify broker credentials and WSS support
- **High battery drain**: Check state transitions in logs

## Security Notes

- Store MQTT credentials securely (consider Android Keystore)
- Use WSS (WebSocket Secure) for encrypted MQTT
- Physical device security is critical with Device Owner privileges
- Disable ADB in production if not needed for remote management

## Development

```bash
# Build debug APK
gradlew.bat assembleDebug

# Install via ADB
adb install app\build\outputs\apk\debug\app-debug.apk

# View logs
adb logcat -s GpsTrackingService:*
```

## License

This project is provided as-is for dedicated GPS tracking use cases.

## Requirements Summary

- **Minimum SDK**: 22 (Android 5.1)
- **Target SDK**: 36 (Android 14)
- **Permissions**: Location, Network, Device Admin
- **Special Requirements**: Device Owner mode

## Support

For detailed setup instructions, see [DEVICE_OWNER_SETUP.md](DEVICE_OWNER_SETUP.md)

