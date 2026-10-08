package com.flareaward.serendip.presentation.common

import android.content.Context
import android.text.format.DateFormat
import com.flareaward.serendip.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Human-readable dates, times and durations. Everything is locale-aware. */
object Formatters {

    fun time(context: Context, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
    }

    fun dateTime(context: Context, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return "${dayLabel(context, date)}, ${time(context, epochMillis, zone)}"
    }

    /** "Today", "Yesterday", "8 October" or "8 October 2025". */
    fun dayLabel(context: Context, date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> context.getString(R.string.day_today)
        today.minusDays(1) -> context.getString(R.string.day_yesterday)
        else -> {
            val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
            DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
        }
    }

    /** "in 37 min", "in 2 h 05 min", "in less than a minute". */
    fun durationUntil(context: Context, targetMillis: Long, nowMillis: Long): String {
        val totalMinutes = ((targetMillis - nowMillis) / 60_000L).coerceAtLeast(0L)
        return when {
            targetMillis - nowMillis < 60_000L -> context.getString(R.string.duration_less_than_minute)
            totalMinutes < 60 -> context.getString(R.string.duration_in_minutes, totalMinutes)
            else -> context.getString(R.string.duration_in_hours_minutes, totalMinutes / 60, totalMinutes % 60)
        }
    }

    /** "30 min", "2 h", "1 h 30 min". */
    fun minutes(context: Context, minutes: Int): String = when {
        minutes < 60 -> context.getString(R.string.minutes_short, minutes)
        minutes % 60 == 0 -> context.getString(R.string.hours_short, minutes / 60)
        else -> context.getString(R.string.hours_minutes_short, minutes / 60, minutes % 60)
    }

    /** "30–60 min" / "1 h – 2 h" style range. */
    fun intervalRange(context: Context, minMinutes: Int, maxMinutes: Int): String =
        context.getString(R.string.interval_range, minutes(context, minMinutes), minutes(context, maxMinutes))
}
