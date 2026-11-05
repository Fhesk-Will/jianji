package com.jianji.app.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Dates {

    private val weekNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun epochDay(d: LocalDate): Int = d.toEpochDay().toInt()

    fun ofEpochDay(v: Int): LocalDate = LocalDate.ofEpochDay(v.toLong())

    fun monthRange(m: YearMonth): Pair<Int, Int> =
        epochDay(m.atDay(1)) to epochDay(m.atEndOfMonth())

    fun monthTitle(m: YearMonth): String = "${m.year}年${m.monthValue}月"

    /** "今天 · 11月4日 周三"（近三天加前缀，更远只有日期） */
    fun dayLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String {
        val prefix = when (today.toEpochDay() - d.toEpochDay()) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            else -> null
        }
        val base = "${d.monthValue}月${d.dayOfMonth}日 ${weekNames[d.dayOfWeek.value - 1]}"
        return if (prefix == null) base else "$prefix · $base"
    }

    // ---- M3 DatePicker 交互用 UTC 毫秒 ----
    fun toUtcMillis(d: LocalDate): Long = d.toEpochDay() * 86_400_000L

    fun fromUtcMillis(ms: Long): LocalDate = LocalDate.ofEpochDay(ms / 86_400_000L)

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    /** 设置页展示："今天 07:00" / "昨天 22:10" / "10月3日 07:00" */
    fun backupTimeLabel(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "尚未备份"
        val zone = ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val n = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)
        return when {
            d == n -> "今天 $time"
            d == n.minusDays(1) -> "昨天 $time"
            else -> "${d.monthValue}月${d.dayOfMonth}日 $time"
        }
    }
}
