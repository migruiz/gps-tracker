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

    interface SingleLocationListener {
        fun onLocationReceived(location: Location, provider: String)
        fun onError(error: String)
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
            callback.onError("Location permission not granted")
            return
        }

        Log.d(TAG, "Requesting single location with ${timeoutMs}ms timeout (requestLocationUpdates)")

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        var isFinished = false
        val handler = android.os.Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (isFinished) return@Runnable
            isFinished = true
            Log.e(TAG, "Location request timed out after ${timeoutMs}ms")
            callback.onError("Location request timed out")
        }
        handler.postDelayed(timeoutRunnable, timeoutMs)

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (isFinished) return@addOnSuccessListener
                    isFinished = true
                    if (location != null) {
                        val provider = location.provider ?: "fused"
                        Log.i(TAG, "Single location received from $provider: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                        callback.onLocationReceived(location, provider)
                    } else {
                        Log.e(TAG, "Single location request returned null")
                        callback.onError("Location unavailable")
                    }
                }.addOnFailureListener { e ->
                    if (isFinished) return@addOnFailureListener
                    isFinished = true
                    Log.e(TAG, "Failed to get single location", e)
                    callback.onError("Failed to get location: ${e.message}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting single location", e)
            callback.onError("Security exception: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LocationManager"
    }
}
