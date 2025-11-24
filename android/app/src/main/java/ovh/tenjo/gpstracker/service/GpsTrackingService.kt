package ovh.tenjo.gpstracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager as AndroidLocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import ovh.tenjo.gpstracker.MainActivity
import ovh.tenjo.gpstracker.R
import ovh.tenjo.gpstracker.config.AppConfig
import ovh.tenjo.gpstracker.location.LocationManager
import ovh.tenjo.gpstracker.model.AppState
import ovh.tenjo.gpstracker.mqtt.HttpApiClient
import ovh.tenjo.gpstracker.utils.AlarmScheduler
import ovh.tenjo.gpstracker.utils.AppHidingManager
import ovh.tenjo.gpstracker.utils.BatteryMonitor
import ovh.tenjo.gpstracker.utils.ConnectivityManager
import ovh.tenjo.gpstracker.receiver.LocationAlarmReceiver
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class GpsTrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var httpClient: HttpApiClient
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appHidingManager: AppHidingManager
    private lateinit var alarmScheduler: AlarmScheduler

    private var currentState: AppState = AppState.IDLE
    private var wakeLock: PowerManager.WakeLock? = null
    private var isProcessingLocation = false

    // Error logging
    private val errorLog = ConcurrentLinkedQueue<ErrorEntry>()
    private val MAX_ERROR_LOG_SIZE = 50

    data class ErrorEntry(
        val timestamp: Long,
        val module: String,
        val message: String
    )

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): GpsTrackingService = this@GpsTrackingService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        locationManager = LocationManager(this)
        httpClient = HttpApiClient(this)
        batteryMonitor = BatteryMonitor(this)
        connectivityManager = ConnectivityManager(this)
        appHidingManager = AppHidingManager(this)
        alarmScheduler = AlarmScheduler(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        // Don't acquire WakeLock here anymore - only acquire when processing location
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LocationAlarmReceiver.ACTION_LOCATION_ALARM -> {
                Log.d(TAG, "Location alarm received")
                handleLocationAlarm()
            }
            ACTION_INITIAL_SETUP -> {
                Log.d(TAG, "Initial setup")
                performInitialSetup()
            }
        }
        return START_NOT_STICKY // Changed from START_STICKY - we want alarm to restart us, not system
    }

    private fun performInitialSetup() {
        // Setup kiosk mode if device owner
        if (connectivityManager.isDeviceOwner()) {
            connectivityManager.enableKioskMode()
            connectivityManager.restrictBackgroundData()
            connectivityManager.enableAlwaysOnVPN()
            connectivityManager.applyAggressivePowerRestrictions()

            Log.i(TAG, "Device owner detected - initiating AGGRESSIVE app removal")
            updateNotification("Stopping non-critical apps...")

            Thread {
                try {
                    val result = appHidingManager.hideNonCriticalApps()
                    Log.i(TAG, "AGGRESSIVE app hiding completed: ${result.message}")

                    Handler(Looper.getMainLooper()).post {
                        updateNotification("Stopped ${result.successCount} apps - Maximum CPU optimization active")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during app hiding: ${e.message}", e)
                    Handler(Looper.getMainLooper()).post {
                        updateNotification("App optimization error - continuing")
                    }
                }
            }.start()
        }

        // Schedule the first alarm
        scheduleNextLocationAlarm()

        // Stop service after scheduling alarm
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Initial setup complete, stopping service")
            stopSelf()
        }, 2000) // Give time for device owner setup to complete
    }

    private fun handleLocationAlarm() {
        if (isProcessingLocation) {
            Log.w(TAG, "Already processing a location update, skipping")
            stopSelf()
            return
        }

        // Acquire WakeLock for this operation only
        acquireWakeLock()

        isProcessingLocation = true
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val isAwakeTime = AppConfig.isAwakeTime(hour, minute)

        if (!isAwakeTime) {
            Log.d(TAG, "Alarm fired but not in awake time, scheduling next alarm")
            isProcessingLocation = false
            scheduleNextLocationAlarm()
            releaseWakeLock()
            stopSelf()
            return
        }

        Log.d(TAG, "Processing location update")
        currentState = AppState.AWAKE
        updateNotification("Getting location...")
        broadcastStateUpdate()

        // Connect HTTP client
        httpClient.connect()

        // Get battery info once at the start
        val batteryInfo = batteryMonitor.getBatteryInfo()

        // Request single location
        locationManager.requestSingleLocation(object : LocationManager.SingleLocationListener {
            override fun onLocationReceived(location: Location, provider: String) {
                Log.d(TAG, "Location received, sending to API with battery info")

                // Send location with battery info in single request
                httpClient.publishLocation(
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    location.time,
                    provider,
                    batteryInfo.level
                )

                // Cleanup after sending
                Handler(Looper.getMainLooper()).postDelayed({
                    cleanupAfterLocationUpdate()
                }, 1000) // Give time for HTTP request to complete

                updateNotification("Location sent ($provider)")
            }

            override fun onLocationTimeout(error: String) {
                Log.e(TAG, "Location timeout: $error")
                logError("Location", error)
                updateNotification("Location error: $error")

                // Cleanup anyway
                Handler(Looper.getMainLooper()).postDelayed({
                    cleanupAfterLocationUpdate()
                }, 500)
            }
        }, 30000L) // 30 second timeout
    }

    private fun cleanupAfterLocationUpdate() {
        // Disconnect HTTP
        httpClient.disconnect()

        // Check if next minute is still in awake period
        val nextMinuteCalendar = Calendar.getInstance()
        nextMinuteCalendar.add(Calendar.MINUTE, 1)
        val nextHour = nextMinuteCalendar.get(Calendar.HOUR_OF_DAY)
        val nextMinute = nextMinuteCalendar.get(Calendar.MINUTE)
        val isNextMinuteAwake = AppConfig.isAwakeTime(nextHour, nextMinute)

        if (isNextMinuteAwake) {
            currentState = AppState.AWAKE
            updateNotification("AWAKE - Waiting for next alarm")
        } else {
            currentState = AppState.IDLE
            updateNotification("IDLE - Next update scheduled")
        }

        broadcastStateUpdate()

        // Schedule next alarm
        scheduleNextLocationAlarm()

        isProcessingLocation = false

        // Release WakeLock
        releaseWakeLock()

        // Stop the service completely - alarm will restart it when needed
        Log.d(TAG, "Location processing complete, stopping service")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Don't cancel alarm here - we want it to fire again!
        // The alarm is only cancelled when explicitly stopping the tracking (not implemented yet)
        releaseWakeLock()
    }

    private fun logError(module: String, message: String) {
        val error = ErrorEntry(System.currentTimeMillis(), module, message)
        errorLog.offer(error)

        while (errorLog.size > MAX_ERROR_LOG_SIZE) {
            errorLog.poll()
        }

        Log.e(TAG, "[$module] $message")
        broadcastStateUpdate()
    }

    private fun scheduleNextLocationAlarm() {
        val isInActiveWindow = alarmScheduler.scheduleNextAlarm()

        if (isInActiveWindow) {
            Log.d(TAG, "Next alarm scheduled for next minute (active period)")
            currentState = AppState.AWAKE
        } else {
            Log.d(TAG, "Next alarm scheduled for next active period")
            currentState = AppState.IDLE
        }

        broadcastStateUpdate()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            Log.d(TAG, "WakeLock already held")
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GPSTracker::LocationUpdate"
        )
        // Acquire for max 2 minutes - enough time to get location and send data
        wakeLock?.acquire(2 * 60 * 1000L)
        Log.d(TAG, "WakeLock acquired for 2 minutes")
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

    fun getLocationStatus(): LocationStatus {
        val androidLocationManager = getSystemService(Context.LOCATION_SERVICE) as AndroidLocationManager
        val gpsEnabled = androidLocationManager.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER)
        val networkEnabled = androidLocationManager.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)

        return LocationStatus(
            gpsEnabled = gpsEnabled,
            networkEnabled = networkEnabled,
            isTracking = isProcessingLocation
        )
    }

    fun getErrorLog(): List<ErrorEntry> {
        return errorLog.toList()
    }

    fun getStateInfo(): StateInfo {
        val batteryInfo = batteryMonitor.getBatteryInfo()
        val locationStatus = getLocationStatus()

        return StateInfo(
            state = currentState,
            httpConnected = httpClient.isConnected(),
            apiEndpoint = AppConfig.API_ENDPOINT,
            deviceId = AppConfig.DEVICE_ID,
            gpsTracking = isProcessingLocation,
            batteryLevel = batteryInfo.level,
            isDeviceOwner = connectivityManager.isDeviceOwner(),
            hiddenAppsCount = 0,
            locationStatus = locationStatus,
            errorLog = getErrorLog()
        )
    }

    data class LocationStatus(
        val gpsEnabled: Boolean,
        val networkEnabled: Boolean,
        val isTracking: Boolean
    )

    data class StateInfo(
        val state: AppState,
        val httpConnected: Boolean,
        val apiEndpoint: String,
        val deviceId: String,
        val gpsTracking: Boolean,
        val batteryLevel: Int,
        val isDeviceOwner: Boolean,
        val hiddenAppsCount: Int,
        val locationStatus: LocationStatus,
        val errorLog: List<ErrorEntry>
    )

    companion object {
        private const val TAG = "GpsTrackingService"
        private const val CHANNEL_ID = "gps_tracking_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STATE_UPDATE = "ovh.tenjo.gpstracker.STATE_UPDATE"
        const val ACTION_INITIAL_SETUP = "ovh.tenjo.gpstracker.INITIAL_SETUP"
    }
}
