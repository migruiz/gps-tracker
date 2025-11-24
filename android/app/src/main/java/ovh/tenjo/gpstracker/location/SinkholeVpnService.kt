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
import android.app.Notification
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
    private val channelId = "vpn_channel"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        // Close previous instance if exists
        vpnInterface?.close()
        vpnInterface = null

        try {
            val builder = Builder()
                .setSession("FirewallVPN")
                .addAddress("10.0.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0) // IPv6
                .setBlocking(true)

            // ONLY allow your intended app
            builder.addDisallowedApplication("ovh.tenjo.gpstracker")
            //builder.addDisallowedApplication("com.google.android.gms")

            // Establish dummy TUN
            vpnInterface = builder.establish()

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                channelId,
                "Firewall VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Firewall VPN Active")
                .setContentText("Only your app can access the network")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()

            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
