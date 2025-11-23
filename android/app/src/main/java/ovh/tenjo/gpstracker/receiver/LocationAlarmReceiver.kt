package ovh.tenjo.gpstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ovh.tenjo.gpstracker.service.GpsTrackingService

class LocationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Location alarm triggered")

        // Start the service to handle this location update
        val serviceIntent = Intent(context, GpsTrackingService::class.java).apply {
            action = ACTION_LOCATION_ALARM
        }
        context.startForegroundService(serviceIntent)
    }

    companion object {
        private const val TAG = "LocationAlarmReceiver"
        const val ACTION_LOCATION_ALARM = "ovh.tenjo.gpstracker.LOCATION_ALARM"
    }
}

