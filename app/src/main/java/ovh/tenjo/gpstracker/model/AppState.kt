package ovh.tenjo.gpstracker.model

enum class AppState {
    IDLE,           // Device in sleep mode, all disabled
    AWAKE,          // Active tracking mode
    BATTERY_CHECK   // Hourly battery check mode
}

