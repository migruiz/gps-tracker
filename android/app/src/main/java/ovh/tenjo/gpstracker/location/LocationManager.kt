package ovh.tenjo.gpstracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

class LocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }



    interface SingleLocationListener {
        fun onLocationReceived(location: Location, provider: String)
        fun onLocationTimeout(error: String)
    }


    private fun hasLocationPermission(): Boolean {
        val hasFineLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Permissions - Fine: $hasFineLocation, Coarse: $hasCoarseLocation")

        // Need at least one location permission, but ideally both
        return hasFineLocation || hasCoarseLocation
    }

    /**
     * Request a single location update with timeout
     * Uses requestLocationUpdates for reliability in a Service context
     */
    fun requestSingleLocation(callback: SingleLocationListener, timeoutMs: Long = 30000L) {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            callback.onLocationTimeout("Location permission not granted")
            return
        }

        Log.d(TAG, "Requesting single location with ${timeoutMs}ms timeout (requestLocationUpdates)")

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10000L
        )
            .setMaxUpdates(1)
            .setMinUpdateIntervalMillis(0)
            .build()

        val handler = android.os.Handler(Looper.getMainLooper())
        var isFinished = false

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                return
                if (isFinished) return
                isFinished = true
                handler.removeCallbacksAndMessages(null)
                fusedLocationClient.removeLocationUpdates(this)

                val location = result.lastLocation
                if (location != null) {
                    val provider = location.provider ?: "fused"
                    Log.i(TAG, "Single location received from $provider: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                    callback.onLocationReceived(location, provider)
                } else {
                    Log.e(TAG, "Single location request returned null")
                    callback.onLocationTimeout("Location unavailable")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "Location availability: ${availability.isLocationAvailable}")
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            handler.postDelayed({
                if (isFinished) return@postDelayed
                isFinished = true
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.e(TAG, "Location request timed out after ${timeoutMs}ms")
                callback.onLocationTimeout("Location request timed out")
            }, timeoutMs)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting single location", e)
            callback.onLocationTimeout("Security exception: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LocationManager"
    }
}
