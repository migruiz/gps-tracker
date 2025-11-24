package ovh.tenjo.gpstracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import ovh.tenjo.gpstracker.config.AppConfig

class LocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private var locationCallback: LocationCallback? = null
    private var isTracking = false

    interface LocationUpdateListener {
        fun onLocationUpdate(location: Location, provider: String)
        fun onLocationError(error: String)
    }

    interface SingleLocationListener {
        fun onLocationReceived(location: Location, provider: String)
        fun onLocationTimeout(error: String)
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

        // Create location request
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            AppConfig.GPS_UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(AppConfig.GPS_UPDATE_INTERVAL_MS / 2)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val provider = location.provider ?: "fused"
                    Log.d(TAG, "Location update from $provider: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                    listener?.onLocationUpdate(location, provider)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "Location availability changed: ${availability.isLocationAvailable}")
                if (!availability.isLocationAvailable) {
                    listener?.onLocationError("Location unavailable")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                isTracking = true
                Log.d(TAG, "Started location updates with Fused Location Provider (interval: ${AppConfig.GPS_UPDATE_INTERVAL_MS}ms)")
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to request location updates", e)
                listener?.onLocationError("Failed to start location updates: ${e.message}")
            }
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
     * Uses getCurrentLocation which automatically handles best provider selection
     */
    fun requestSingleLocation(callback: SingleLocationListener, timeoutMs: Long = 30000L) {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            callback.onLocationTimeout("Location permission not granted")
            return
        }

        Log.d(TAG, "Requesting single location with ${timeoutMs}ms timeout")

        try {
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    val provider = location.provider ?: "fused"
                    Log.i(TAG, "Single location received from $provider: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                    callback.onLocationReceived(location, provider)
                } else {
                    Log.e(TAG, "Single location request returned null")
                    callback.onLocationTimeout("Location unavailable")
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get single location", e)
                callback.onLocationTimeout("Failed to get location: ${e.message}")
            }

            // Handle timeout
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                cancellationTokenSource.cancel()
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
