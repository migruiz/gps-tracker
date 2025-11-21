package ovh.tenjo.gpstracker.config

import ovh.tenjo.gpstracker.model.TimeSlot

object AppConfig {
    // MQTT Configuration
    const val MQTT_BROKER_URL = "wss://your-mqtt-broker.com:8883"
    const val MQTT_CLIENT_ID = "gps-tracker-device"
    const val MQTT_USERNAME = "your-username"
    const val MQTT_PASSWORD = "your-password"
    const val MQTT_TOPIC_LOCATION = "gps/location"
    const val MQTT_TOPIC_BATTERY = "gps/battery/warning"

    // GPS Configuration
    const val GPS_UPDATE_INTERVAL_MS = 30000L // 30 seconds
    const val GPS_FASTEST_INTERVAL_MS = 15000L // 15 seconds

    // Battery Configuration
    const val BATTERY_CHECK_INTERVAL_MS = 3600000L // 1 hour
    const val BATTERY_LOW_THRESHOLD = 20 // 20% battery level

    // Awake Time Slots (hardcoded schedule)
    val AWAKE_TIME_SLOTS = listOf(
        TimeSlot(8, 0, 9, 0),     // 08:00 -> 09:00
        TimeSlot(14, 0, 15, 0),   // 14:00 -> 15:00
        TimeSlot(18, 0, 19, 0)    // 18:00 -> 19:00
    )

    // Check if current time is within any awake time slot
    fun isAwakeTime(hour: Int, minute: Int): Boolean {
        return AWAKE_TIME_SLOTS.any { it.isInTimeSlot(hour, minute) }
    }
}

