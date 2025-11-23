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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ovh.tenjo.gpstracker.config.AppConfig
import ovh.tenjo.gpstracker.model.AppState
import ovh.tenjo.gpstracker.service.GpsTrackingService
import ovh.tenjo.gpstracker.ui.theme.GPSTrackerTheme
import ovh.tenjo.gpstracker.location.SinkholeVpnService
import ovh.tenjo.gpstracker.utils.VolumeControlManager
import ovh.tenjo.gpstracker.utils.CallManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var trackingService: GpsTrackingService? = null
    private var serviceBound = false

    private lateinit var volumeControlManager: VolumeControlManager
    lateinit var callManager: CallManager

    private var stateInfo by mutableStateOf<GpsTrackingService.StateInfo?>(null)
    private var isDeviceOwner by mutableStateOf(false)
    var isIncomingCall by mutableStateOf(false)
    var showCallMomConfirmation by mutableStateOf(false)
    var showCallDadConfirmation by mutableStateOf(false)
    var isInCall by mutableStateOf(false)
    var currentCallWith by mutableStateOf<String?>(null) // "Mom" or "Dad"

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
            // updateStateInfo()
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ovh.tenjo.gpstracker.receiver.PhoneCallReceiver.ACTION_INCOMING_CALL -> {
                    isIncomingCall = true
                    Log.d(TAG, "UI: Incoming call detected - showing answer button")
                }
                ovh.tenjo.gpstracker.receiver.PhoneCallReceiver.ACTION_CALL_ENDED -> {
                    isIncomingCall = false
                    Log.d(TAG, "UI: Call ended - hiding answer button")
                }
            }
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

        // Initialize managers
        volumeControlManager = VolumeControlManager(this)
        callManager = CallManager(this)

        // Check if device owner and apply volume controls
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)

        if (isDeviceOwner) {
            // Mute all volumes and disable volume buttons in DO mode
            volumeControlManager.muteAllVolumes()
            volumeControlManager.disableVolumeButtons()
            Log.d(TAG, "Device Owner mode: Volumes muted and buttons disabled")
        }

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

        // Register call state receiver
        val callFilter = IntentFilter().apply {
            addAction(ovh.tenjo.gpstracker.receiver.PhoneCallReceiver.ACTION_INCOMING_CALL)
            addAction(ovh.tenjo.gpstracker.receiver.PhoneCallReceiver.ACTION_CALL_ENDED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callStateReceiver, callFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callStateReceiver, callFilter)
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
            unregisterReceiver(callStateReceiver)
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
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG
        )

        // Add background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        // Add foreground service location for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        // Add ANSWER_PHONE_CALLS for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
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
            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_INITIAL_SETUP
            }
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Started tracking service with initial setup")
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
    val mainActivity = context as? MainActivity

    // Full-screen incoming call dialog
    if (mainActivity?.isIncomingCall == true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF4CAF50)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "📞",
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 100.dp.value.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "INCOMING CALL",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "From Mom or Dad",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = {
                        mainActivity.callManager.acceptCall()
                        mainActivity.isIncomingCall = false
                        mainActivity.isInCall = true
                        mainActivity.currentCallWith = "Mom/Dad"
                        Log.d("DebugUI", "Answer button pressed")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White
                    )
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📱",
                            style = MaterialTheme.typography.displayMedium
                        )
                        Text(
                            text = "ANSWER",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }
        return // Don't show main UI when incoming call
    }

    // In-call dialog overlay
    if (mainActivity?.isInCall == true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2196F3)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "☎️",
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 100.dp.value.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "IN CALL",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Connected with ${mainActivity.currentCallWith ?: "Mom/Dad"}",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = {
                        mainActivity.isInCall = false
                        mainActivity.currentCallWith = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935) // Red for end call
                    )
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📴",
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White
                        )
                        Text(
                            text = "END CALL",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
        return // Don't show main UI when in call
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Emergency Call Card at the TOP - Call Mom or Dad
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Emergency Calls",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    text = "Only calls from Mom or Dad are allowed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            mainActivity?.showCallMomConfirmation = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📞",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                text = "Call Mom",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Button(
                        onClick = {
                            mainActivity?.showCallDadConfirmation = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📞",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                text = "Call Dad",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                Text(
                    text = "Mom: ${AppConfig.MOM_PHONE_NUMBER}\nDad: ${AppConfig.DAD_PHONE_NUMBER}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

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

    // Confirmation Dialog for Call Mom
    if (mainActivity?.showCallMomConfirmation == true) {
        AlertDialog(
            onDismissRequest = { mainActivity.showCallMomConfirmation = false },
            title = {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "📞",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Call Mom?",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            },
            text = {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "You are about to call:",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AppConfig.MOM_PHONE_NUMBER,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        mainActivity?.callManager?.callMom()
                        mainActivity?.showCallMomConfirmation = false
                        // Show in-call dialog
                        mainActivity?.isInCall = true
                        mainActivity?.currentCallWith = "Mom"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text(
                        text = "YES, CALL MOM",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { mainActivity?.showCallMomConfirmation = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        )
    }

    // Confirmation Dialog for Call Dad
    if (mainActivity?.showCallDadConfirmation == true) {
        AlertDialog(
            onDismissRequest = { mainActivity.showCallDadConfirmation = false },
            title = {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "📞",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Call Dad?",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            },
            text = {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "You are about to call:",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AppConfig.DAD_PHONE_NUMBER,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        mainActivity?.callManager?.callDad()
                        mainActivity?.showCallDadConfirmation = false
                        // Show in-call dialog
                        mainActivity?.isInCall = true
                        mainActivity?.currentCallWith = "Dad"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text(
                        text = "YES, CALL DAD",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { mainActivity?.showCallDadConfirmation = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        )
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