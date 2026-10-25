package com.echolog.app.core.util

import com.echolog.app.core.common.Constants
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间工具（架构 2.1/7.2）。
 *
 * 唯一时间口径：
 * - 内部存储与跨层传递一律使用 epoch millis（Long）；
 * - dayId 为本地时区日期字符串 `yyyy-MM-dd`（如 `2026-10-24`）；
 * - 展示层经本对象转换为本地时区格式，禁止在数据库存格式化时间字符串。
 */
object TimeUtils {

    private val dayIdFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(Constants.DAY_ID_PATTERN, Locale.US)

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

    private val monthFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年MM月", Locale.US)

    private val dayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM月dd日", Locale.US)

    /** 当前 epoch millis。 */
    fun currentEpochMs(): Long = System.currentTimeMillis()

    /** 今天的 dayId（本地时区）。 */
    fun todayDayId(): String = formatDayId(currentEpochMs())

    /** epoch millis -> dayId。 */
    fun formatDayId(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(dayIdFormatter)

    /** LocalDate -> dayId。 */
    fun formatDayId(date: LocalDate): String = date.format(dayIdFormatter)

    /** dayId -> LocalDate；格式非法返回 null。 */
    fun parseDayId(dayId: String): LocalDate? =
        runCatching { LocalDate.parse(dayId, dayIdFormatter) }.getOrNull()

    /** epoch millis -> `HH:mm`（本地时区）。 */
    fun formatTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter)

    /** epoch millis -> `MM-dd HH:mm`（本地时区）。 */
    fun formatDateTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

    /** dayId -> `yyyy年MM月` 月份分组标签（历史列表按月分组）；格式非法原样返回。 */
    fun formatMonthLabel(dayId: String): String =
        parseDayId(dayId)?.format(monthFormatter) ?: dayId

    /** dayId -> `MM月dd日` 日期标签（今天页日期展示）；格式非法原样返回。 */
    fun formatDayLabel(dayId: String): String =
        parseDayId(dayId)?.format(dayFormatter) ?: dayId

    /**
     * 紧凑时长：`1h23m` / `5m` / `40s`（不足 1s 记 `0s`）。
     * 用于列表徽标等空间敏感场景。
     */
    fun formatDurationCompact(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** 时钟式时长：`HH:MM:SS`。用于监听时长、播放进度等场景。 */
    fun formatDurationClock(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            totalSeconds / 3600L,
            (totalSeconds % 3600L) / 60L,
            totalSeconds % 60L,
        )
    }

    /** dayId 当天 00:00 的 epoch millis（本地时区）；格式非法返回 0。 */
    fun startOfDayEpochMs(dayId: String): Long {
        val date = parseDayId(dayId) ?: return 0L
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** dayId 当天 23:59:59.999 的 epoch millis（本地时区）；格式非法返回 0。 */
    fun endOfDayEpochMs(dayId: String): Long {
        val date = parseDayId(dayId) ?: return 0L
        return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1L
    }

    /** 两个时间戳是否同一天（本地时区）。 */
    fun isSameDay(epochMsA: Long, epochMsB: Long): Boolean =
        formatDayId(epochMsA) == formatDayId(epochMsB)

    /**
     * 当前时刻在一天中的第几分钟（0..1439，本地时区）。
     *
     * 用户需求 4：模板录音按「一天中的第几分钟」判定是否落在课表区间内。
     */
    fun currentMinuteOfDay(): Int = minuteOfDay(currentEpochMs())

    /** epoch millis → 一天中的第几分钟（0..1439，本地时区）。 */
    fun minuteOfDay(epochMs: Long): Int {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        return zoned.hour * 60 + zoned.minute
    }
}
