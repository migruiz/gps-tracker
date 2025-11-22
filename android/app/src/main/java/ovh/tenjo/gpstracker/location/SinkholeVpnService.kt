package ovh.tenjo.gpstracker.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class SinkholeVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile private var running = false

    // Track blocked apps and their attempts
    private val blockedAttempts = ConcurrentHashMap<String, BlockedAppInfo>()

    data class BlockedAppInfo(
        val appName: String,
        val packageName: String,
        var attemptCount: Int,
        var lastAttemptTime: Long
    )

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SinkholeVpnService = this@SinkholeVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getBlockedAttempts(): List<BlockedAppInfo> {
        return blockedAttempts.values.sortedByDescending { it.lastAttemptTime }
    }

    fun isVpnActive(): Boolean {
        return vpnInterface != null && running
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create and show foreground notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Internet Sinkhole Active")
            .setContentText("Blocking all traffic except this app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Build and establish the VPN
        try {
            val builder = Builder()
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0) // Intercept all traffic

            // CRITICAL: Bypass this app so it can still use the internet
            try {
                builder.addDisallowedApplication(packageName)
                Log.d(TAG, "Bypassed app: $packageName")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Failed to disallow app from VPN", e)
            }

            builder.setSession("SinkholeVPN")
            builder.setBlocking(false)

            // Establish the VPN interface - MUST keep reference
            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                Log.i(TAG, "Sinkhole VPN established successfully - all other apps blocked")

                // Start packet monitoring thread
                running = true
                vpnThread = Thread(VpnRunnable()).apply {
                    name = "VpnThread"
                    start()
                }
            } else {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN", e)
            stopSelf()
        }

        return Service.START_STICKY
    }

    private inner class VpnRunnable : Runnable {
        override fun run() {
            val vpnFd = vpnInterface ?: return
            val inputStream = FileInputStream(vpnFd.fileDescriptor)
            val outputStream = FileOutputStream(vpnFd.fileDescriptor)
            val packet = ByteBuffer.allocate(32767)

            try {
                while (running && !Thread.interrupted()) {
                    packet.clear()
                    val length = inputStream.read(packet.array())

                    if (length > 0) {
                        // Log packet attempt (simplified - real implementation would parse IP headers)
                        logBlockedAttempt()

                        // We don't forward the packet - it's blocked (sinkhole)
                    } else if (length < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Error in VPN thread", e)
                }
            }
        }
    }

    private fun logBlockedAttempt() {
        // Simplified: Just track that something tried to send data
        // A full implementation would parse IP headers to identify the app
        val timestamp = System.currentTimeMillis()

        // For now, create a generic entry
        val key = "blocked_apps"
        blockedAttempts.compute(key) { _, existing ->
            if (existing != null) {
                existing.copy(
                    attemptCount = existing.attemptCount + 1,
                    lastAttemptTime = timestamp
                )
            } else {
                BlockedAppInfo(
                    appName = "Other Apps",
                    packageName = "various",
                    attemptCount = 1,
                    lastAttemptTime = timestamp
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false

        vpnThread?.interrupt()
        vpnThread = null

        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.i(TAG, "VPN interface closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.w(TAG, "VPN permission revoked")
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Sinkhole",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for Internet Sinkhole VPN service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SinkholeVpnService"
        private const val CHANNEL_ID = "sinkhole_vpn_channel"
        private const val NOTIFICATION_ID = 42
    }
}
