package ovh.tenjo.gpstracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        // Check provider availability
        val gpsEnabled = locationManager.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "Provider status - GPS: $gpsEnabled, Network: $networkEnabled")

        if (!gpsEnabled && !networkEnabled) {
            Log.e(TAG, "No location providers are enabled")
            listener?.onLocationError("No location providers enabled")
            return
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val provider = location.provider ?: "unknown"
                Log.d(TAG, "Location update from $provider: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
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
            var providersRequested = 0

            // Request location updates from GPS provider
            if (gpsEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        AndroidLocationManager.GPS_PROVIDER,
                        AppConfig.GPS_UPDATE_INTERVAL_MS,
                        0f, // No minimum distance
                        locationListener!!
                    )
                    providersRequested++
                    Log.d(TAG, "Successfully requested GPS updates (interval: ${AppConfig.GPS_UPDATE_INTERVAL_MS}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request GPS updates", e)
                }
            }

            // Also request network provider updates
            if (networkEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        AndroidLocationManager.NETWORK_PROVIDER,
                        AppConfig.GPS_UPDATE_INTERVAL_MS,
                        0f,
                        locationListener!!
                    )
                    providersRequested++
                    Log.d(TAG, "Successfully requested Network updates (interval: ${AppConfig.GPS_UPDATE_INTERVAL_MS}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request Network updates", e)
                }
            }

            if (providersRequested == 0) {
                Log.e(TAG, "Failed to request updates from any provider")
                listener?.onLocationError("Failed to register location listeners")
                return
            }

            isTracking = true
            Log.d(TAG, "Started location updates from $providersRequested provider(s)")
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
     * Waits for locations from both providers and returns the best one
     */
    fun requestSingleLocation(callback: SingleLocationListener, timeoutMs: Long = 30000L) {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            callback.onLocationTimeout("Location permission not granted")
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var locationReceived = false
        var bestLocation: Location? = null
        var bestProvider: String? = null

        // Wait a bit to collect locations from multiple providers
        val collectionTimeMs = 5000L

        val singleLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!locationReceived) {
                    val provider = location.provider ?: "unknown"
                    Log.d(TAG, "Single location received from $provider: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")

                    // Keep the best location (most accurate)
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                        bestProvider = provider
                        Log.d(TAG, "Updated best location to $provider (accuracy: ${location.accuracy}m)")
                    } else {
                        Log.d(TAG, "Keeping previous best location from $bestProvider (accuracy: ${bestLocation!!.accuracy}m vs ${location.accuracy}m)")
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "Location provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Location provider disabled: $provider")
            }
        }

        try {
            var providersRequested = 0

            // Try GPS first
            if (locationManager.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    AndroidLocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    singleLocationListener,
                    Looper.getMainLooper()
                )
                providersRequested++
                Log.d(TAG, "Requested single location from GPS provider")
            } else {
                Log.w(TAG, "GPS provider not enabled")
            }

            // Also request from network provider as backup
            if (locationManager.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    AndroidLocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    singleLocationListener,
                    Looper.getMainLooper()
                )
                providersRequested++
                Log.d(TAG, "Requested single location from Network provider")
            } else {
                Log.w(TAG, "Network provider not enabled")
            }

            if (providersRequested == 0) {
                Log.e(TAG, "No location providers available")
                callback.onLocationTimeout("No location providers available")
                return
            }

            Log.d(TAG, "Waiting ${collectionTimeMs}ms to collect locations from $providersRequested provider(s)")

            // Wait to collect locations from multiple providers
            handler.postDelayed({
                if (!locationReceived && bestLocation != null) {
                    locationReceived = true
                    try {
                        locationManager.removeUpdates(singleLocationListener)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error removing location updates", e)
                    }
                    Log.i(TAG, "Returning best location from $bestProvider (accuracy: ${bestLocation!!.accuracy}m)")
                    callback.onLocationReceived(bestLocation!!, bestProvider!!)
                }
            }, collectionTimeMs)

            // Set final timeout
            handler.postDelayed({
                if (!locationReceived) {
                    locationReceived = true
                    try {
                        locationManager.removeUpdates(singleLocationListener)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error removing location updates on timeout", e)
                    }

                    if (bestLocation != null) {
                        Log.w(TAG, "Timeout reached but have location from $bestProvider, returning it")
                        callback.onLocationReceived(bestLocation!!, bestProvider!!)
                    } else {
                        Log.e(TAG, "Single location request timed out with no location received")
                        callback.onLocationTimeout("Location request timed out after ${timeoutMs}ms")
                    }
                }
            }, timeoutMs)

            Log.d(TAG, "Requested single location update with ${timeoutMs}ms timeout")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting single location", e)
            callback.onLocationTimeout("Security exception: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LocationManager"
    }
}
