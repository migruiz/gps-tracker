package ovh.tenjo.gpstracker.config

import ovh.tenjo.gpstracker.model.TimeSlot

object AppConfig {
    // Debug Configuration
    const val DEBUG_MODE = true // Set to false for production
    const val VERBOSE_LOGGING = true // Extra detailed logs

    // HTTP API Configuration
    const val API_ENDPOINT = "https://gps.tenjo.ovh/api/location" // Changed to HTTP
    const val DEVICE_ID = "gps-tracker-device"

    // GPS Configuration
    const val GPS_UPDATE_INTERVAL_MS = 10000L // 30 seconds
    const val GPS_FASTEST_INTERVAL_MS = 15000L // 15 seconds



    // Phone Configuration - Whitelisted contacts
    // TODO: Replace these with actual phone numbers before deployment
    const val MOM_PHONE_NUMBER = "+353894108085"
    const val DAD_PHONE_NUMBER = "+353894195242"

    // Awake Time Slots (hardcoded schedule)
    val AWAKE_TIME_SLOTS = listOf(
        TimeSlot(7, 50, 8, 40),
        TimeSlot(14, 10, 15, 0),
        TimeSlot(18, 45, 20, 0)
    )

    // Check if current time is within any awake time slot
    fun isAwakeTime(hour: Int, minute: Int): Boolean {
        return AWAKE_TIME_SLOTS.any { it.isInTimeSlot(hour, minute) }
    }
}
