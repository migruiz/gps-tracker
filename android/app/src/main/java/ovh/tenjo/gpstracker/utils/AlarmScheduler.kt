package ovh.tenjo.gpstracker.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import ovh.tenjo.gpstracker.config.AppConfig
import ovh.tenjo.gpstracker.receiver.LocationAlarmReceiver
import java.util.*

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule the next location update alarm
     * @return true if alarm is scheduled for active period, false if scheduled for next active period
     */
    fun scheduleNextAlarm(): Boolean {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val isCurrentlyAwake = AppConfig.isAwakeTime(currentHour, currentMinute)

        val nextAlarmTime = if (isCurrentlyAwake) {
            // Schedule for next minute
            calendar.add(Calendar.MINUTE, 1)

            // Check if next minute is still in awake period
            val nextHour = calendar.get(Calendar.HOUR_OF_DAY)
            val nextMinute = calendar.get(Calendar.MINUTE)

            if (!AppConfig.isAwakeTime(nextHour, nextMinute)) {
                // Next minute is in idle, schedule for next awake period
                findNextAwakeTime(calendar)
                false
            } else {
                true
            }
        } else {
            // Currently idle, schedule for next awake period
            findNextAwakeTime(calendar)
            false
        }

        scheduleAlarm(calendar.timeInMillis)

        Log.d(TAG, "Next alarm scheduled for: ${calendar.time}")
        return nextAlarmTime
    }

    private fun findNextAwakeTime(calendar: Calendar) {
        val startCalendar = calendar.clone() as Calendar

        // Check up to 7 days ahead to find next awake slot
        for (dayOffset in 0..7) {
            val testCalendar = startCalendar.clone() as Calendar
            testCalendar.add(Calendar.DAY_OF_YEAR, dayOffset)

            val dayOfWeek = testCalendar.get(Calendar.DAY_OF_WEEK)

            // Skip weekends (Saturday=7, Sunday=1)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                continue
            }

            for (timeSlot in AppConfig.AWAKE_TIME_SLOTS) {
                val slotCalendar = testCalendar.clone() as Calendar
                slotCalendar.set(Calendar.HOUR_OF_DAY, timeSlot.startHour)
                slotCalendar.set(Calendar.MINUTE, timeSlot.startMinute)
                slotCalendar.set(Calendar.SECOND, 0)
                slotCalendar.set(Calendar.MILLISECOND, 0)

                if (slotCalendar.after(startCalendar)) {
                    calendar.timeInMillis = slotCalendar.timeInMillis
                    Log.d(TAG, "Next awake time scheduled for: ${calendar.time} (Day: ${getDayName(calendar.get(Calendar.DAY_OF_WEEK))})")
                    return
                }
            }
        }

        // Fallback: schedule for next Monday at first time slot
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        val firstSlot = AppConfig.AWAKE_TIME_SLOTS.first()
        calendar.set(Calendar.HOUR_OF_DAY, firstSlot.startHour)
        calendar.set(Calendar.MINUTE, firstSlot.startMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        Log.d(TAG, "Fallback: scheduling for Monday at first time slot: ${calendar.time}")
    }

    private fun getDayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> "Unknown"
        }
    }

    private fun scheduleAlarm(triggerAtMillis: Long) {
        val intent = Intent(context, LocationAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use setExactAndAllowWhileIdle for precise timing even in Doze mode
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // On Android 12+, if exact alarm permission is not granted, fall back to inexact alarm
            Log.w(TAG, "Exact alarm permission not granted, using inexact alarm", e)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    fun cancelAlarm() {
        val intent = Intent(context, LocationAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Alarm cancelled")
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val ALARM_REQUEST_CODE = 1001
    }
}
