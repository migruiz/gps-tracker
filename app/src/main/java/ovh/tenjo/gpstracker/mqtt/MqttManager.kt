package ovh.tenjo.gpstracker.mqtt

import android.content.Context
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import ovh.tenjo.gpstracker.config.AppConfig

class MqttManager(private val context: Context) {

    private var mqttClient: MqttAndroidClient? = null
    private var isConnected = false

    interface ConnectionCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    private var connectionCallback: ConnectionCallback? = null

    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }

    fun connect() {
        try {
            if (isConnected) {
                Log.d(TAG, "Already connected")
                return
            }

            // Create MQTT client with WSS support
            mqttClient = MqttAndroidClient(
                context,
                AppConfig.MQTT_BROKER_URL,
                AppConfig.MQTT_CLIENT_ID
            )

            val options = MqttConnectOptions().apply {
                userName = AppConfig.MQTT_USERNAME
                password = AppConfig.MQTT_PASSWORD.toCharArray()
                isCleanSession = true
                connectionTimeout = 30
                keepAliveInterval = 60
                isAutomaticReconnect = true

                // Enable SSL/TLS for WSS connections
                socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost", cause)
                    isConnected = false
                    connectionCallback?.onDisconnected()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // Not expecting incoming messages for this app
                    Log.d(TAG, "Message arrived on topic: $topic")
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.v(TAG, "Message delivered")
                }
            })

            Log.d(TAG, "Connecting to MQTT broker: ${AppConfig.MQTT_BROKER_URL}")

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Successfully connected to MQTT broker")
                    isConnected = true
                    connectionCallback?.onConnected()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to connect to MQTT broker", exception)
                    isConnected = false
                    connectionCallback?.onError(exception?.message ?: "Connection failed")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to MQTT", e)
            isConnected = false
            connectionCallback?.onError(e.message ?: "Unknown error")
        }
    }

    fun disconnect() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect(null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "Disconnected from MQTT broker")
                        isConnected = false
                        connectionCallback?.onDisconnected()
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Error disconnecting from MQTT", exception)
                    }
                })
            }
            mqttClient?.unregisterResources()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    fun publishLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long) {
        if (!isConnected || mqttClient?.isConnected != true) {
            Log.w(TAG, "Cannot publish - not connected")
            return
        }

        try {
            val payload = JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("timestamp", timestamp)
                put("device_id", AppConfig.MQTT_CLIENT_ID)
            }

            val message = MqttMessage(payload.toString().toByteArray()).apply {
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(AppConfig.MQTT_TOPIC_LOCATION, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Published location: $latitude, $longitude")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish location", exception)
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error publishing location", e)
        }
    }

    fun publishBatteryWarning(batteryLevel: Int, isCharging: Boolean) {
        if (!isConnected || mqttClient?.isConnected != true) {
            Log.w(TAG, "Cannot publish battery warning - not connected")
            return
        }

        try {
            val payload = JSONObject().apply {
                put("battery_level", batteryLevel)
                put("is_charging", isCharging)
                put("timestamp", System.currentTimeMillis())
                put("device_id", AppConfig.MQTT_CLIENT_ID)
            }

            val message = MqttMessage(payload.toString().toByteArray()).apply {
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(AppConfig.MQTT_TOPIC_BATTERY, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Published battery warning: $batteryLevel%")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish battery warning", exception)
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error publishing battery warning", e)
        }
    }

    fun isConnected(): Boolean = isConnected && (mqttClient?.isConnected == true)

    fun getBrokerUrl(): String = AppConfig.MQTT_BROKER_URL

    fun getClientId(): String = AppConfig.MQTT_CLIENT_ID

    companion object {
        private const val TAG = "MqttManager"
    }
}
