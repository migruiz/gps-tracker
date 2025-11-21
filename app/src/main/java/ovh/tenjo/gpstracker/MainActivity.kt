package ovh.tenjo.gpstracker

import android.Manifest
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ovh.tenjo.gpstracker.config.AppConfig
import ovh.tenjo.gpstracker.model.AppState
import ovh.tenjo.gpstracker.service.GpsTrackingService
import ovh.tenjo.gpstracker.ui.theme.GPSTrackerTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var trackingService: GpsTrackingService? = null
    private var serviceBound = false

    private var stateInfo by mutableStateOf<GpsTrackingService.StateInfo?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GpsTrackingService.LocalBinder
            trackingService = binder.getService()
            serviceBound = true
            updateStateInfo()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            serviceBound = false
        }
    }

    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStateInfo()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startTrackingService()
        } else {
            Log.e(TAG, "Some permissions not granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        checkAndRequestPermissions()

        // Register broadcast receiver
        val filter = IntentFilter(GpsTrackingService.ACTION_STATE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateUpdateReceiver, filter)
        }

        setContent {
            GPSTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DebugUI(stateInfo)
                }
            }
        }

        // Start lock task mode if device owner
        try {
            startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "Could not start lock task mode", e)
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service
        val intent = Intent(this, GpsTrackingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(stateUpdateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        // Add background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        // Add foreground service location for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted")
            startTrackingService()
        }
    }

    private fun startTrackingService() {
        if (!isServiceRunning(GpsTrackingService::class.java)) {
            val intent = Intent(this, GpsTrackingService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Started tracking service")
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun updateStateInfo() {
        stateInfo = trackingService?.getStateInfo()
    }

    private fun showPermissionError() {
        // Update UI to show error - for now just log
        Log.e(TAG, "Cannot start tracking - permissions not granted")
        // You could show a dialog or snackbar here
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun DebugUI(stateInfo: GpsTrackingService.StateInfo?) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "GPS Tracker Debug UI",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Current Time
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Current Time",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date()),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // App State
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Current State",
                    style = MaterialTheme.typography.titleMedium
                )

                val stateColor = when (stateInfo?.state) {
                    AppState.IDLE -> Color.Gray
                    AppState.AWAKE -> Color.Green
                    AppState.BATTERY_CHECK -> Color.Yellow
                    null -> Color.Red
                }

                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(stateColor)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stateInfo?.state?.name ?: "UNKNOWN",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }
            }
        }

        // Device Owner Status
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Device Owner Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (stateInfo?.isDeviceOwner == true) "✓ Device Owner" else "✗ Not Device Owner",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (stateInfo?.isDeviceOwner == true) Color.Green else Color.Red
                )
            }
        }

        // Battery Info
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Battery",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Level: ${stateInfo?.batteryLevel ?: "N/A"}%",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Charging: ${if (stateInfo?.isCharging == true) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // MQTT Info (only show when awake)
        if (stateInfo?.state == AppState.AWAKE) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "MQTT Connection",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    if (stateInfo?.mqttConnected == true) Color.Green else Color.Red
                                )
                        )
                        Text(
                            text = if (stateInfo?.mqttConnected == true) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Text(
                        text = "Broker: ${stateInfo?.mqttBroker ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "Client ID: ${stateInfo?.mqttClientId ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // GPS Tracking Info
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GPS Tracking",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    if (stateInfo?.gpsTracking == true) Color.Green else Color.Red
                                )
                        )
                        Text(
                            text = if (stateInfo?.gpsTracking == true) "Active" else "Inactive",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Text(
                        text = "Update interval: ${AppConfig.GPS_UPDATE_INTERVAL_MS / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Awake Time Slots
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Configured Awake Time Slots",
                    style = MaterialTheme.typography.titleMedium
                )

                AppConfig.AWAKE_TIME_SLOTS.forEach { timeSlot ->
                    Text(
                        text = "• $timeSlot",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // System Info
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "System Information",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Battery check: Every ${AppConfig.BATTERY_CHECK_INTERVAL_MS / 60000} minutes",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Battery threshold: ${AppConfig.BATTERY_LOW_THRESHOLD}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}