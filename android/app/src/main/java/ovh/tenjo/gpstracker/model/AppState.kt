package ovh.tenjo.gpstracker.model

enum class AppState {
    IDLE,           // Device in sleep mode, waiting for next alarm
    AWAKE           // Active tracking mode (processing location update)
}
