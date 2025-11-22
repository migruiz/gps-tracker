package ovh.tenjo.gpstracker.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class SinkholeVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

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

    override fun onDestroy() {
        super.onDestroy()
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

    override fun onBind(intent: Intent?): IBinder? = null

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
