package ovh.tenjo.gpstracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import ovh.tenjo.gpstracker.MainActivity
import ovh.tenjo.gpstracker.R
import ovh.tenjo.gpstracker.config.AppConfig
import ovh.tenjo.gpstracker.location.LocationManager
import ovh.tenjo.gpstracker.model.AppState
import ovh.tenjo.gpstracker.mqtt.MqttManager
import ovh.tenjo.gpstracker.utils.BatteryMonitor
import ovh.tenjo.gpstracker.utils.ConnectivityManager
import java.util.*

class GpsTrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var mqttManager: MqttManager
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var connectivityManager: ConnectivityManager

    private var currentState: AppState = AppState.IDLE
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val stateCheckRunnable = object : Runnable {
        override fun run() {
            checkAndUpdateState()
            handler.postDelayed(this, 60000) // Check every minute
        }
    }

    private val batteryCheckRunnable = object : Runnable {
        override fun run() {
            performBatteryCheck()
            handler.postDelayed(this, AppConfig.BATTERY_CHECK_INTERVAL_MS)
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): GpsTrackingService = this@GpsTrackingService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        locationManager = LocationManager(this)
        mqttManager = MqttManager(this)
        batteryMonitor = BatteryMonitor(this)
        connectivityManager = ConnectivityManager(this)

        setupLocationListener()
        setupMqttCallback()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        acquireWakeLock()

        // Setup kiosk mode if device owner
        if (connectivityManager.isDeviceOwner()) {
            connectivityManager.enableKioskMode()
            connectivityManager.restrictBackgroundData()
        }

        // Start state checking
        handler.post(stateCheckRunnable)

        // Start battery checking (every hour)
        handler.post(batteryCheckRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        handler.removeCallbacks(stateCheckRunnable)
        handler.removeCallbacks(batteryCheckRunnable)

        locationManager.stopLocationUpdates()
        mqttManager.disconnect()

        releaseWakeLock()
    }

    private fun setupLocationListener() {
        locationManager.setLocationUpdateListener(object : LocationManager.LocationUpdateListener {
            override fun onLocationUpdate(location: Location) {
                if (currentState == AppState.AWAKE) {
                    mqttManager.publishLocation(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        location.time
                    )
                    updateNotification("Location sent: ${location.latitude}, ${location.longitude}")
                    broadcastStateUpdate()
                }
            }

            override fun onLocationError(error: String) {
                Log.e(TAG, "Location error: $error")
                updateNotification("Location error: $error")
            }
        })
    }

    private fun setupMqttCallback() {
        mqttManager.setConnectionCallback(object : MqttManager.ConnectionCallback {
            override fun onConnected() {
                Log.d(TAG, "MQTT connected")
                updateNotification("MQTT Connected")
                broadcastStateUpdate()
            }

            override fun onDisconnected() {
                Log.d(TAG, "MQTT disconnected")
                updateNotification("MQTT Disconnected")
                broadcastStateUpdate()
            }

            override fun onError(error: String) {
                Log.e(TAG, "MQTT error: $error")
                updateNotification("MQTT Error: $error")
            }
        })
    }

    private fun checkAndUpdateState() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val shouldBeAwake = AppConfig.isAwakeTime(hour, minute)

        if (shouldBeAwake && currentState != AppState.AWAKE) {
            transitionToAwakeState()
        } else if (!shouldBeAwake && currentState == AppState.AWAKE) {
            transitionToIdleState()
        }
    }

    private fun transitionToAwakeState() {
        Log.d(TAG, "Transitioning to AWAKE state")
        currentState = AppState.AWAKE

        // Enable connectivity
        connectivityManager.setAirplaneMode(false)
        Thread.sleep(2000) // Wait for airplane mode to disable
        connectivityManager.setMobileDataEnabled(true)
        Thread.sleep(2000) // Wait for mobile data to enable

        // Connect to MQTT
        mqttManager.connect()

        // Start GPS tracking
        locationManager.startLocationUpdates()

        updateNotification("AWAKE - Tracking active")
        broadcastStateUpdate()
    }

    private fun transitionToIdleState() {
        Log.d(TAG, "Transitioning to IDLE state")
        currentState = AppState.IDLE

        // Stop GPS tracking
        locationManager.stopLocationUpdates()

        // Disconnect MQTT
        mqttManager.disconnect()

        // Disable connectivity to save battery
        connectivityManager.setMobileDataEnabled(false)
        connectivityManager.setWifiEnabled(false)
        connectivityManager.setAirplaneMode(true)

        updateNotification("IDLE - Power saving mode")
        broadcastStateUpdate()
    }

    private fun performBatteryCheck() {
        Log.d(TAG, "Performing battery check")

        val previousState = currentState
        currentState = AppState.BATTERY_CHECK

        val batteryInfo = batteryMonitor.getBatteryInfo()

        // Try WiFi first, then mobile data
        var networkEnabled = false

        if (connectivityManager.isWifiConnected()) {
            networkEnabled = true
        } else {
            // Enable mobile data temporarily
            connectivityManager.setAirplaneMode(false)
            Thread.sleep(2000)
            connectivityManager.setMobileDataEnabled(true)
            Thread.sleep(3000) // Wait for connection
            networkEnabled = true
        }

        if (networkEnabled) {
            // Connect to MQTT if not already connected
            if (!mqttManager.isConnected()) {
                mqttManager.connect()
                Thread.sleep(3000) // Wait for MQTT connection
            }

            // Send battery warning if below threshold
            if (batteryInfo.level <= AppConfig.BATTERY_LOW_THRESHOLD && !batteryInfo.isCharging) {
                mqttManager.publishBatteryWarning(batteryInfo.level, batteryInfo.isCharging)
            }
        }

        // Return to previous state
        currentState = previousState

        // If was idle, disable network again
        if (previousState == AppState.IDLE) {
            mqttManager.disconnect()
            connectivityManager.setMobileDataEnabled(false)
            connectivityManager.setAirplaneMode(true)
        }

        broadcastStateUpdate()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GPSTracker::WakeLock"
        )
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPS tracking service notifications"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStateUpdate() {
        val intent = Intent(ACTION_STATE_UPDATE)
        sendBroadcast(intent)
    }

    fun getCurrentState(): AppState = currentState

    fun getStateInfo(): StateInfo {
        val batteryInfo = batteryMonitor.getBatteryInfo()
        return StateInfo(
            state = currentState,
            mqttConnected = mqttManager.isConnected(),
            mqttBroker = mqttManager.getBrokerUrl(),
            mqttClientId = mqttManager.getClientId(),
            gpsTracking = locationManager.isTracking(),
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.isCharging,
            isDeviceOwner = connectivityManager.isDeviceOwner()
        )
    }

    data class StateInfo(
        val state: AppState,
        val mqttConnected: Boolean,
        val mqttBroker: String,
        val mqttClientId: String,
        val gpsTracking: Boolean,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isDeviceOwner: Boolean
    )

    companion object {
        private const val TAG = "GpsTrackingService"
        private const val CHANNEL_ID = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STATE_UPDATE = "ovh.tenjo.gpstracker.STATE_UPDATE"
    }
}
