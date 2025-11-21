package ovh.tenjo.gpstracker.utils

import android.util.Log

object DebugLogger {

    private const val GLOBAL_TAG = "GPSTracker"

    fun d(tag: String, message: String) {
        Log.d("$GLOBAL_TAG:$tag", message)
    }

    fun i(tag: String, message: String) {
        Log.i("$GLOBAL_TAG:$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$GLOBAL_TAG:$tag", message, throwable)
        } else {
            Log.w("$GLOBAL_TAG:$tag", message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$GLOBAL_TAG:$tag", message, throwable)
        } else {
            Log.e("$GLOBAL_TAG:$tag", message)
        }
    }

    fun v(tag: String, message: String) {
        Log.v("$GLOBAL_TAG:$tag", message)
    }

    // State transition logging
    fun logStateTransition(from: String, to: String, reason: String) {
        Log.i("$GLOBAL_TAG:StateTransition", "[$from] -> [$to] because: $reason")
    }

    // Network event logging
    fun logNetworkEvent(event: String, details: String) {
        Log.d("$GLOBAL_TAG:Network", "$event: $details")
    }

    // Location event logging
    fun logLocationEvent(event: String, lat: Double? = null, lon: Double? = null) {
        val location = if (lat != null && lon != null) " at ($lat, $lon)" else ""
        Log.d("$GLOBAL_TAG:Location", "$event$location")
    }
}

