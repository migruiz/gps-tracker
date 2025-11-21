package ovh.tenjo.gpstracker.mqtt

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import ovh.tenjo.gpstracker.config.AppConfig
import java.util.concurrent.Executors

class MqttManager(private val context: Context) {

    private var mqttClient: MqttClient? = null
    private var isConnected = false
    private val executor = Executors.newSingleThreadExecutor()

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
        executor.execute {
            try {
                val persistence = MemoryPersistence()
                mqttClient = MqttClient(
                    AppConfig.MQTT_BROKER_URL,
                    AppConfig.MQTT_CLIENT_ID,
                    persistence
                )

                val options = MqttConnectOptions().apply {
                    userName = AppConfig.MQTT_USERNAME
                    password = AppConfig.MQTT_PASSWORD.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Connection lost", cause)
                        isConnected = false
                        connectionCallback?.onDisconnected()
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        // Not expecting incoming messages
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d(TAG, "Message delivered")
                    }
                })

                mqttClient?.connect(options)
                isConnected = true
                Log.d(TAG, "Connected to MQTT broker")
                connectionCallback?.onConnected()

            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MQTT", e)
                isConnected = false
                connectionCallback?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
                isConnected = false
                Log.d(TAG, "Disconnected from MQTT broker")
                connectionCallback?.onDisconnected()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from MQTT", e)
            }
        }
    }

    fun publishLocation(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long) {
        if (!isConnected) {
            Log.w(TAG, "Cannot publish - not connected")
            return
        }

        executor.execute {
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

                mqttClient?.publish(AppConfig.MQTT_TOPIC_LOCATION, message)
                Log.d(TAG, "Published location: $latitude, $longitude")

            } catch (e: Exception) {
                Log.e(TAG, "Error publishing location", e)
            }
        }
    }

    fun publishBatteryWarning(batteryLevel: Int, isCharging: Boolean) {
        if (!isConnected) {
            Log.w(TAG, "Cannot publish battery warning - not connected")
            return
        }

        executor.execute {
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

                mqttClient?.publish(AppConfig.MQTT_TOPIC_BATTERY, message)
                Log.d(TAG, "Published battery warning: $batteryLevel%")

            } catch (e: Exception) {
                Log.e(TAG, "Error publishing battery warning", e)
            }
        }
    }

    fun isConnected(): Boolean = isConnected

    fun getBrokerUrl(): String = AppConfig.MQTT_BROKER_URL

    fun getClientId(): String = AppConfig.MQTT_CLIENT_ID

    companion object {
        private const val TAG = "MqttManager"
    }
}
