package com.echolog.app.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * [TimeUtils] 单元测试（QA：架构 7.2 唯一时间口径）。
 *
 * dayId 为本地时区日期；测试固定时区为 Asia/Shanghai（UTC+8，无 DST）保证确定性。
 */
class TimeUtilsTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(shanghai))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    // ── dayId 边界 ──

    @Test
    fun `当天零点的epoch格式化为当天dayId`() {
        val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(shanghai).toInstant().toEpochMilli()
        assertEquals("2026-10-24", TimeUtils.formatDayId(midnight))
    }

    @Test
    fun `午夜前一毫秒归前一天dayId`() {
        val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(shanghai).toInstant().toEpochMilli()
        assertEquals("2026-10-23", TimeUtils.formatDayId(midnight - 1))
    }

    @Test
    fun `当天最后一毫秒仍归当天dayId`() {
        val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(shanghai).toInstant().toEpochMilli()
        assertEquals("2026-10-24", TimeUtils.formatDayId(midnight + 86_400_000L - 1))
    }

    @Test
    fun `startOfDay与endOfDay覆盖自然日边界`() {
        val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(shanghai).toInstant().toEpochMilli()
        assertEquals(midnight, TimeUtils.startOfDayEpochMs("2026-10-24"))
        assertEquals(midnight + 86_400_000L - 1, TimeUtils.endOfDayEpochMs("2026-10-24"))
    }

    @Test
    fun `非法dayId的边界查询返回0`() {
        assertEquals(0L, TimeUtils.startOfDayEpochMs("not-a-day"))
        assertEquals(0L, TimeUtils.endOfDayEpochMs(""))
        assertNull(TimeUtils.parseDayId("2026-13-99"))
        assertNull(TimeUtils.parseDayId("24-10-2026"))
    }

    @Test
    fun `isSameDay按本地时区判定`() {
        val day1 = LocalDate.of(2026, 10, 24).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val sameDayLate = day1 + 23 * 3_600_000L
        val nextDay = day1 + 86_400_000L
        assertTrue(TimeUtils.isSameDay(day1, sameDayLate))
        assertFalse(TimeUtils.isSameDay(day1, nextDay))
    }

    @Test
    fun `formatDayId与LocalDate重载一致`() {
        val date = LocalDate.of(2026, 1, 5)
        assertEquals("2026-01-05", TimeUtils.formatDayId(date))
        assertEquals("2026-01-05", TimeUtils.formatDayId(date.atStartOfDay(shanghai).toInstant().toEpochMilli()))
    }

    // ── 展示格式 ──

    @Test
    fun `formatTime输出HHmm本地时间`() {
        val ms = LocalDate.of(2026, 10, 24)
            .atTime(9, 5)
            .atZone(shanghai)
            .toInstant()
            .toEpochMilli()
        assertEquals("09:05", TimeUtils.formatTime(ms))
    }

    @Test
    fun `formatDateTime输出月日时分`() {
        val ms = LocalDate.of(2026, 10, 24)
            .atTime(14, 30)
            .atZone(shanghai)
            .toInstant()
            .toEpochMilli()
        assertEquals("10-24 14:30", TimeUtils.formatDateTime(ms))
    }

    @Test
    fun `月份与日期标签`() {
        assertEquals("2026年10月", TimeUtils.formatMonthLabel("2026-10-24"))
        assertEquals("10月24日", TimeUtils.formatDayLabel("2026-10-24"))
        // 非法 dayId 原样返回（展示层容错）
        assertEquals("bad-id", TimeUtils.formatMonthLabel("bad-id"))
        assertEquals("bad-id", TimeUtils.formatDayLabel("bad-id"))
    }

    @Test
    fun `紧凑时长格式`() {
        assertEquals("0s", TimeUtils.formatDurationCompact(0L))
        assertEquals("40s", TimeUtils.formatDurationCompact(40_000L))
        assertEquals("5m", TimeUtils.formatDurationCompact(5 * 60_000L))
        assertEquals("1h23m", TimeUtils.formatDurationCompact(3_600_000L + 23 * 60_000L + 30_000L))
        // 负值防御
        assertEquals("0s", TimeUtils.formatDurationCompact(-5_000L))
    }

    @Test
    fun `时钟式时长格式`() {
        assertEquals("00:00:00", TimeUtils.formatDurationClock(0L))
        assertEquals("00:01:05", TimeUtils.formatDurationClock(65_000L))
        assertEquals("02:03:04", TimeUtils.formatDurationClock(2 * 3_600_000L + 3 * 60_000L + 4_000L))
        assertEquals("00:00:00", TimeUtils.formatDurationClock(-1_000L))
    }
}
