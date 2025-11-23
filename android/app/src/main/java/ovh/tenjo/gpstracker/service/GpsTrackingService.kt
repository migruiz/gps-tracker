package ovh.tenjo.gpstracker.service

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.location.LocationManager as AndroidLocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import ovh.tenjo.gpstracker.MainActivity
import ovh.tenjo.gpstracker.R
import ovh.tenjo.gpstracker.config.AppConfig
import ovh.tenjo.gpstracker.location.LocationManager
import ovh.tenjo.gpstracker.location.SinkholeVpnService
import ovh.tenjo.gpstracker.model.AppState
import ovh.tenjo.gpstracker.mqtt.HttpApiClient
import ovh.tenjo.gpstracker.utils.BatteryMonitor
import ovh.tenjo.gpstracker.utils.ConnectivityManager
import ovh.tenjo.gpstracker.utils.AppHidingManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class GpsTrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var httpClient: HttpApiClient
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appHidingManager: AppHidingManager

    private var currentState: AppState = AppState.IDLE
    private var wakeLock: PowerManager.WakeLock? = null

    // Error logging
    private val errorLog = ConcurrentLinkedQueue<ErrorEntry>()
    private val MAX_ERROR_LOG_SIZE = 50

    data class ErrorEntry(
        val timestamp: Long,
        val module: String,
        val message: String
    )



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
        httpClient = HttpApiClient(this)
        batteryMonitor = BatteryMonitor(this)
        connectivityManager = ConnectivityManager(this)
        appHidingManager = AppHidingManager(this)

        setupLocationListener()
        setupHttpCallback()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        acquireWakeLock()



        // Setup kiosk mode if device owner
        if (connectivityManager.isDeviceOwner()) {
            connectivityManager.enableKioskMode()
            connectivityManager.restrictBackgroundData()
            connectivityManager.enableAlwaysOnVPN()

            // Apply aggressive power restrictions to minimize CPU usage
            connectivityManager.applyAggressivePowerRestrictions()

            // AGGRESSIVE app removal - hide all non-critical apps to save CPU/battery
            Log.i(TAG, "Device owner detected - initiating AGGRESSIVE app removal")
            updateNotification("Stopping non-critical apps...")

            // Run in background thread to avoid blocking service startup
            Thread {
                try {
                    val result = appHidingManager.hideNonCriticalApps()
                    Log.i(TAG, "AGGRESSIVE app hiding completed: ${result.message}")

                    handler.post {
                        updateNotification("Stopped ${result.successCount} apps - Maximum CPU optimization active")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during app hiding: ${e.message}", e)
                    handler.post {
                        updateNotification("App optimization error - continuing")
                    }
                }
            }.start()

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
        httpClient.disconnect()



        releaseWakeLock()
    }

    private fun logError(module: String, message: String) {
        val error = ErrorEntry(System.currentTimeMillis(), module, message)
        errorLog.offer(error)

        // Keep log size manageable
        while (errorLog.size > MAX_ERROR_LOG_SIZE) {
            errorLog.poll()
        }

        Log.e(TAG, "[$module] $message")
        broadcastStateUpdate()
    }

    private fun setupLocationListener() {
        locationManager.setLocationUpdateListener(object : LocationManager.LocationUpdateListener {
            override fun onLocationUpdate(location: Location, provider: String) {
                if (currentState == AppState.AWAKE) {
                    httpClient.publishLocation(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        location.time,
                        provider
                    )
                    updateNotification("Location sent ($provider): ${location.latitude}, ${location.longitude}")
                    broadcastStateUpdate()
                }
            }

            override fun onLocationError(error: String) {
                logError("Location", error)
                updateNotification("Location error: $error")
            }
        })
    }

    private fun setupHttpCallback() {
        httpClient.setConnectionCallback(object : HttpApiClient.ConnectionCallback {
            override fun onConnected() {
                Log.d(TAG, "HTTP client ready")
                updateNotification("HTTP Ready")
                broadcastStateUpdate()
            }

            override fun onDisconnected() {
                Log.d(TAG, "HTTP client disconnected")
                updateNotification("HTTP Disconnected")
                broadcastStateUpdate()
            }

            override fun onError(error: String) {
                logError("HTTP", error)
                updateNotification("HTTP Error: $error")
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



        // Connect HTTP client (just marks as ready)
        httpClient.connect()

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

        // Disconnect HTTP client
        httpClient.disconnect()



        updateNotification("IDLE - Power saving mode")
        broadcastStateUpdate()
    }

    private fun performBatteryCheck() {
        Log.d(TAG, "Performing battery check")

        val previousState = currentState
        currentState = AppState.BATTERY_CHECK

        val batteryInfo = batteryMonitor.getBatteryInfo()



        // Connect HTTP client if not already
        if (!httpClient.isConnected()) {
            httpClient.connect()
            Thread.sleep(1000)
        }

        // Send battery warning if below threshold
        if (batteryInfo.level <= AppConfig.BATTERY_LOW_THRESHOLD && !batteryInfo.isCharging) {
            httpClient.publishBatteryWarning(batteryInfo.level, batteryInfo.isCharging)
        }

        // Return to previous state
        currentState = previousState

        // If was idle, disable network again
        if (previousState == AppState.IDLE) {
            httpClient.disconnect()
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

    fun getLocationStatus(): LocationStatus {
        val androidLocationManager = getSystemService(Context.LOCATION_SERVICE) as AndroidLocationManager
        val gpsEnabled = androidLocationManager.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER)
        val networkEnabled = androidLocationManager.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)

        return LocationStatus(
            gpsEnabled = gpsEnabled,
            networkEnabled = networkEnabled,
            isTracking = locationManager.isTracking()
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
            gpsTracking = locationManager.isTracking(),
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.isCharging,
            isDeviceOwner = connectivityManager.isDeviceOwner(),
            hiddenAppsCount = 0,//appHidingManager.getHiddenAppsCount()
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
        val isCharging: Boolean,
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
    }
}
