package ovh.tenjo.gpstracker.model

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

    override fun toString(): String {
        return String.format("%02d:%02d -> %02d:%02d", startHour, startMinute, endHour, endMinute)
    }
}

