package ovh.tenjo.gpstracker.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class BatteryMonitor(private val context: Context) {

    fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level >= 0 && scale > 0) {
            (level.toFloat() / scale.toFloat() * 100).toInt()
        } else {
            -1
        }
    }



    fun getBatteryInfo(): BatteryInfo {
        val level = getBatteryLevel()

        Log.d(TAG, "Battery: $level%")

        return BatteryInfo(level)
    }

    data class BatteryInfo(
        val level: Int
    )

    companion object {
        private const val TAG = "BatteryMonitor"
    }
}

