package ovh.tenjo.gpstracker.model

import java.util.Calendar

data class TimeSlot(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    fun isInTimeSlot(hour: Int, minute: Int): Boolean {
        val currentMinutes = hour * 60 + minute
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        return currentMinutes in startMinutes until endMinutes
    }

    fun isInTimeSlot(hour: Int, minute: Int, dayOfWeek: Int): Boolean {
        // Check if it's a weekday (Monday=2, Friday=6 in Calendar)
        if (dayOfWeek < Calendar.MONDAY || dayOfWeek > Calendar.FRIDAY) {
            return false
        }
        return isInTimeSlot(hour, minute)
    }

    override fun toString(): String {
        return String.format("%02d:%02d -> %02d:%02d", startHour, startMinute, endHour, endMinute)
    }
}
