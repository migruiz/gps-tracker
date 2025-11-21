package ovh.tenjo.gpstracker.mqtt

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ovh.tenjo.gpstracker.config.AppConfig
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HttpApiClient(private val context: Context) {

    // Create a trust manager that accepts all certificates (for testing only!)
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            // Only apply SSL bypass if using HTTPS
            if (AppConfig.API_ENDPOINT.startsWith("https://")) {
                try {
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, SecureRandom())
                    sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                    hostnameVerifier { _, _ -> true } // Accept all hostnames
                    Log.w(TAG, "SSL certificate validation disabled for testing")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable SSL verification", e)
                }
            }
        }
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    interface ConnectionCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    private var connectionCallback: ConnectionCallback? = null
    private var isConnected = false

    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }

    fun connect() {
        // For HTTP, we don't need persistent connection
        // Just mark as "connected" immediately
        Log.d(TAG, "HTTP client ready: ${AppConfig.API_ENDPOINT}")
        isConnected = true
        connectionCallback?.onConnected()
    }

    fun disconnect() {
        Log.d(TAG, "HTTP client disconnected")
        isConnected = false
        connectionCallback?.onDisconnected()
    }

    fun publishLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long, provider: String) {
        val data = mapOf(
            "type" to "location",
            "latitude" to latitude,
            "longitude" to longitude,
            "accuracy" to accuracy,
            "timestamp" to timestamp,
            "provider" to provider,
            "device_id" to AppConfig.DEVICE_ID
        )

        postData(data, "location")
    }

    fun publishBatteryWarning(batteryLevel: Int, isCharging: Boolean) {
        val data = mapOf(
            "type" to "battery_warning",
            "battery_level" to batteryLevel,
            "is_charging" to isCharging,
            "timestamp" to System.currentTimeMillis(),
            "device_id" to AppConfig.DEVICE_ID
        )

        postData(data, "battery")
    }

    private fun postData(data: Map<String, Any>, dataType: String) {
        val json = gson.toJson(data)
        val body = json.toRequestBody(JSON)

        val request = Request.Builder()
            .url(AppConfig.API_ENDPOINT)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "GPS-Tracker-Android")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to post $dataType data: ${e.message}", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully posted $dataType data: ${response.code}")
                        Log.v(TAG, "Response: ${response.body?.string()}")
                    } else {
                        Log.e(TAG, "Failed to post $dataType data: ${response.code} ${response.message}")
                    }
                }
            }
        })
    }

    fun isConnected(): Boolean = isConnected

    fun getBrokerUrl(): String = AppConfig.API_ENDPOINT

    fun getClientId(): String = AppConfig.DEVICE_ID

    companion object {
        private const val TAG = "HttpApiClient"
    }
}
