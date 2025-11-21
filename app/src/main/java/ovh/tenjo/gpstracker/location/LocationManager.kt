package ovh.tenjo.gpstracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import ovh.tenjo.gpstracker.config.AppConfig

class LocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private var locationCallback: LocationCallback? = null
    private var isTracking = false

    interface LocationUpdateListener {
        fun onLocationUpdate(location: Location)
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

        val locationRequest = LocationRequest.create().apply {
            interval = AppConfig.GPS_UPDATE_INTERVAL_MS
            fastestInterval = AppConfig.GPS_FASTEST_INTERVAL_MS
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")
                    listener?.onLocationUpdate(location)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location not available")
                    listener?.onLocationError("Location not available")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
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

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
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

