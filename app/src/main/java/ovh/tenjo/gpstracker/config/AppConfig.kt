package ovh.tenjo.gpstracker.config

import ovh.tenjo.gpstracker.model.TimeSlot

object AppConfig {
    // Debug Configuration
    const val DEBUG_MODE = true // Set to false for production
    const val VERBOSE_LOGGING = true // Extra detailed logs

    // HTTP API Configuration
    const val API_ENDPOINT = "https://tenjo.requestcatcher.com/test"
    const val DEVICE_ID = "gps-tracker-device"

    // GPS Configuration
    const val GPS_UPDATE_INTERVAL_MS = 30000L // 30 seconds
    const val GPS_FASTEST_INTERVAL_MS = 15000L // 15 seconds

    // Battery Configuration
    const val BATTERY_CHECK_INTERVAL_MS = 3600000L // 1 hour
    const val BATTERY_LOW_THRESHOLD = 20 // 20% battery level

    // Awake Time Slots (hardcoded schedule)
    val AWAKE_TIME_SLOTS = listOf(
        TimeSlot(8, 0, 9, 0),     // 08:00 -> 09:00
        TimeSlot(11, 0, 15, 0),   // 14:00 -> 15:00
        TimeSlot(18, 0, 19, 0)    // 18:00 -> 19:00
    )

    // Check if current time is within any awake time slot
    fun isAwakeTime(hour: Int, minute: Int): Boolean {
        return AWAKE_TIME_SLOTS.any { it.isInTimeSlot(hour, minute) }
    }
}
