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
import android.util.Log
import androidx.core.app.NotificationCompat

class SinkholeVpnService : VpnService() {

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

        val builder = Builder()
        builder.addAddress("192.168.0.1", 24)
        builder.addRoute("0.0.0.0", 0) // Intercept all traffic
        try {
            builder.addDisallowedApplication(packageName) // Let this app bypass VPN
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to disallow app from VPN", e)
        }
        builder.setSession("SinkholeVPN")
        builder.establish() // Do not process packets, just blackhole
        Log.i(TAG, "Sinkhole VPN established")
        return Service.START_STICKY
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
