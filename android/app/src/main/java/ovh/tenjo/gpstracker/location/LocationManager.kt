package ovh.tenjo.gpstracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import ovh.tenjo.gpstracker.config.AppConfig

class LocationManager(private val context: Context) {

    private val locationManager: AndroidLocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as AndroidLocationManager
    }

    private var locationListener: LocationListener? = null
    private var isTracking = false

    interface LocationUpdateListener {
        fun onLocationUpdate(location: Location, provider: String)
        fun onLocationError(error: String)
    }

    private var listener: LocationUpdateListener? = null

    fun setLocationUpdateListener(listener: LocationUpdateListener) {
        this.listener = listener
    }

    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            listener?.onLocationError("Location permission not granted")
            return
        }

        if (isTracking) {
            Log.d(TAG, "Already tracking location")
            return
        }

        // Check if GPS provider is available
        if (!locationManager.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider is not enabled")
            listener?.onLocationError("GPS is disabled")
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val provider = location.provider ?: "unknown"
                Log.d(TAG, "Location update from $provider: ${location.latitude}, ${location.longitude}")
                listener?.onLocationUpdate(location, provider)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.d(TAG, "Location status changed: $provider, status: $status")
            }

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Location provider disabled: $provider")
                listener?.onLocationError("Location provider disabled: $provider")
            }
        }

        try {
            // Request location updates from GPS provider
            locationManager.requestLocationUpdates(
                AndroidLocationManager.GPS_PROVIDER,
                AppConfig.GPS_UPDATE_INTERVAL_MS,
                0f, // No minimum distance
                locationListener!!
            )

            // Also try network provider as backup

            if (locationManager.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    AndroidLocationManager.NETWORK_PROVIDER,
                    AppConfig.GPS_UPDATE_INTERVAL_MS,
                    0f,
                    locationListener!!
                )
            }


            isTracking = true
            Log.d(TAG, "Started location updates")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting location updates", e)
            listener?.onLocationError("Security exception: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        if (!isTracking) {
            Log.d(TAG, "Not tracking location")
            return
        }

        locationListener?.let {
            locationManager.removeUpdates(it)
            isTracking = false
            Log.d(TAG, "Stopped location updates")
        }
    }

    fun isTracking(): Boolean = isTracking

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "LocationManager"
    }
}
