package ovh.tenjo.gpstracker

import android.Manifest
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.net.VpnService
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
import ovh.tenjo.gpstracker.location.SinkholeVpnService
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
            startSinkholeVpn()
        } else {
            Log.e(TAG, "Some permissions not granted")
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // VPN permission granted, start the service
            startVpnServiceInternal()
        } else {
            // Permission not granted, show a message to the user
            Log.e(TAG, "VPN permission not granted")
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
                    DebugUI(stateInfo, this@MainActivity)
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
            startSinkholeVpn()
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

    fun refreshInfo() {
        updateStateInfo()
    }

    private fun startSinkholeVpn() {
        try {
            // Check if VPN permission is already granted
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                // Need to request VPN permission (normal app mode)
                Log.d(TAG, "Requesting VPN permission via dialog")
                try {
                    vpnPermissionLauncher.launch(prepareIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch VPN permission dialog", e)
                    startVpnServiceInternal()
                }
            } else {
                // Permission already granted (Device Owner or previously authorized)
                Log.d(TAG, "VPN permission already granted")
                startVpnServiceInternal()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
        }
    }

    private fun startVpnServiceInternal() {
        if (!isServiceRunning(SinkholeVpnService::class.java)) {
            val intent = Intent(this, SinkholeVpnService::class.java)
            try {
                ContextCompat.startForegroundService(this, intent)
                Log.d(TAG, "Started Sinkhole VPN service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Sinkhole VPN service", e)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun DebugUI(stateInfo: GpsTrackingService.StateInfo?, context: Context) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "GPS Tracker Debug UI",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = {
                    (context as? MainActivity)?.refreshInfo()
                },
                modifier = Modifier.height(40.dp)
            ) {
                Text("Refresh")
            }
        }

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

        // Location Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Location Status",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (stateInfo?.locationStatus?.gpsEnabled == true) Color.Green else Color.Red
                            )
                    )
                    Text(
                        text = "GPS Provider: ${if (stateInfo?.locationStatus?.gpsEnabled == true) "Enabled" else "DISABLED"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (stateInfo?.locationStatus?.networkEnabled == true) Color.Green else Color.Gray
                            )
                    )
                    Text(
                        text = "Network Provider: ${if (stateInfo?.locationStatus?.networkEnabled == true) "Enabled" else "Disabled"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (stateInfo?.locationStatus?.isTracking == true) Color.Green else Color.Gray
                            )
                    )
                    Text(
                        text = "Tracking Active: ${if (stateInfo?.locationStatus?.isTracking == true) "Yes" else "No"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (stateInfo?.locationStatus?.gpsEnabled == false) {
                    Text(
                        text = "⚠️ GPS is disabled! Enable it in system settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // VPN Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Internet Sinkhole (VPN)",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (stateInfo?.vpnStatus?.isActive == true) Color.Green else Color.Red
                            )
                    )
                    Text(
                        text = if (stateInfo?.vpnStatus?.isActive == true) "Active - Blocking all other apps" else "Inactive",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "Blocks all network traffic except this app",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Show blocked apps attempts
                val blockedAttempts = stateInfo?.vpnStatus?.blockedAttempts ?: emptyList()
                if (blockedAttempts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Blocked Network Attempts:",
                        style = MaterialTheme.typography.titleSmall
                    )

                    blockedAttempts.take(5).forEach { attempt ->
                        val timeAgo = formatTimeAgo(attempt.lastAttemptTime)
                        Text(
                            text = "• ${attempt.appName}: ${attempt.attemptCount} attempts ($timeAgo)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
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

                // Show hidden apps count if device owner
                if (stateInfo?.isDeviceOwner == true) {
                    Text(
                        text = "Hidden Apps: ${stateInfo.hiddenAppsCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "System Optimized for Battery",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
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

        // HTTP Info (only show when awake)
        if (stateInfo?.state == AppState.AWAKE) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HTTP API Connection",
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
                                    if (stateInfo?.httpConnected == true) Color.Green else Color.Red
                                )
                        )
                        Text(
                            text = if (stateInfo?.httpConnected == true) "Ready" else "Not Ready",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Text(
                        text = "Endpoint: ${stateInfo?.apiEndpoint ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "Device ID: ${stateInfo?.deviceId ?: "N/A"}",
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

        // Error Log Card
        val errorLog = stateInfo?.errorLog ?: emptyList()
        if (errorLog.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recent Errors (${errorLog.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    errorLog.take(10).forEach { error ->
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(error.timestamp))
                        Text(
                            text = "[$timeStr] ${error.module}: ${error.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
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

fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> "${diff / 86400000}d ago"
    }
}