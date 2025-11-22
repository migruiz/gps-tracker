package ovh.tenjo.gpstracker.location

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.IBinder
import android.util.Log

class SinkholeVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    companion object {
        private const val TAG = "SinkholeVpnService"
    }
}

