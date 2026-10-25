package com.echolog.app.domain.model

/**
 * 领域模型：时间区间（用户需求 4：模板录音的课表式时间段）。
 *
 * 以「一天中的第几分钟」表示（0..1439），避免跨天/时区歧义；
 * [endMinute] 为开区间上界（即 [endMinute] 那一分钟不再属于本区间）。
 *
 * 跨午夜区间（如 23:00 → 01:00）以 [startMinute] > [endMinute] 表达，
 * [contains] 已按环绕语义处理。
 */
data class TimeRange(
    val startMinute: Int,
    val endMinute: Int,
    val label: String = "",
) {

    /** 区间是否覆盖 [minuteOfDay]（含起始、不含结束；跨午夜按环绕处理）。 */
    fun contains(minuteOfDay: Int): Boolean {
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY)
        return if (start <= end) {
            minuteOfDay in start until end
        } else {
            // 跨午夜：start → 24:00 与 00:00 → end 两段
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /** 区间时长（分钟；跨午夜按环绕计算）。 */
    val durationMinutes: Int
        get() {
            val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
            val end = endMinute.coerceIn(0, MINUTES_PER_DAY)
            return if (start <= end) end - start else (MINUTES_PER_DAY - start) + end
        }

    /** `HH:mm-HH:mm` 展示串。 */
    fun display(): String = "${formatClock(startMinute)}-${formatClock(endMinute)}"

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** 分钟数 → `HH:mm`。 */
        fun formatClock(minuteOfDay: Int): String {
            val safe = minuteOfDay.coerceIn(0, MINUTES_PER_DAY)
            return "%02d:%02d".format(safe / 60, safe % 60)
        }

        /**
         * 解析 `HH:mm` → 分钟数；非法返回 null。
         */
        fun parseClock(text: String): Int? {
            val parts = text.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].trim().toIntOrNull() ?: return null
            val minute = parts[1].trim().toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}

/**
 * 领域模型：全天录音模板（用户需求 4：模板录音）。
 *
 * 一份模板描述「一天中哪些时间段需要录音」——适配学生课表；
 * [enabled] 为总开关（关闭时模板不生效）。
 */
data class ScheduleTemplate(
    val name: String = "",
    val ranges: List<TimeRange> = emptyList(),
    val enabled: Boolean = false,
) {

    /** 当前时刻（一天中的第几分钟）是否落在任一区间内。 */
    fun isActiveAt(minuteOfDay: Int): Boolean =
        enabled && ranges.any { it.contains(minuteOfDay) }

    /** 模板内是否有可执行的区间。 */
    val isUsable: Boolean
        get() = ranges.isNotEmpty()
}
