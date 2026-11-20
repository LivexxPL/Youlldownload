package com.youlldownload.numbers.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns cell values into display text using the cell's TSK.FormatStructArchive when there is one:
 * format_type = 1, decimal_places = 2, currency_code = 3, show_thousands_separator = 5,
 * duration_style = 7, base = 8, date_time_format = 14, duration_unit_largest = 15, duration_unit_smallest = 16.
 * Custom formats fall back to sensible defaults.
 */
internal class CellFormatter(private val locale: Locale) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)

    fun number(value: BigDecimal, format: Proto?): String {
        val type = format?.int(1) ?: 0
        val places = format?.long(2)?.toInt()?.takeIf { it in 0..20 } // 253 = "auto"
        val grouping = format?.bool(5) ?: false
        return when (type) {
            DECIMAL, TEXT, FRACTION, CUSTOM_NUMBER -> decimal(value, places, grouping)
            CURRENCY, CUSTOM_CURRENCY -> currency(value, format?.string(3), places, grouping)
            PERCENT -> decimal(value.movePointRight(2), places, grouping) + "%"
            SCIENTIFIC -> scientific(value, places ?: 2)
            BASE -> {
                val base = format?.int(8)?.takeIf { it in 2..36 } ?: 10
                value.setScale(0, RoundingMode.HALF_UP).toBigInteger().toString(base).uppercase()
            }
            CHECKBOX -> if (value.signum() > 0) CHECKED else UNCHECKED
            RATING -> STAR.repeat(value.toInt().coerceIn(0, 5))
            else -> decimal(value, null, grouping)
        }
    }

    fun bool(value: Boolean, format: Proto?): String = when (format?.int(1)) {
        CHECKBOX -> if (value) CHECKED else UNCHECKED
        else -> if (value) "TRUE" else "FALSE"
    }

    fun date(unixMillis: Long, format: Proto?): String {
        val pattern = format?.string(14)?.takeIf { it.isNotBlank() }
        if (pattern != null) {
            try {
                return SimpleDateFormat(pattern, locale).apply { timeZone = UTC }.format(Date(unixMillis))
            } catch (_: IllegalArgumentException) {
                // Pattern uses something SimpleDateFormat doesn't know; use the default below.
            }
        }
        val hasTime = unixMillis % 86_400_000L != 0L
        return SimpleDateFormat(if (hasTime) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd", locale)
            .apply { timeZone = UTC }.format(Date(unixMillis))
    }

    /** Durations are stored in seconds. Default output looks like "2h 24m". */
    fun duration(seconds: Double, format: Proto?): String {
        val negative = seconds < 0
        var rest = Math.round(Math.abs(seconds))
        val units = listOf(WEEK to 604_800L, DAY to 86_400L, HOUR to 3_600L, MINUTE to 60L, SECOND to 1L)
        val largest = format?.int(15)?.takeIf { it > 0 } ?: WEEK
        val smallest = format?.int(16)?.takeIf { it > 0 } ?: SECOND
        val style = format?.int(7) ?: 1
        val used = units.filter { it.first in largest..smallest }.ifEmpty { units }
        val parts = ArrayList<Pair<Int, Long>>()
        for ((i, unit) in used.withIndex()) {
            val n = if (i == used.lastIndex) Math.round(rest.toDouble() / unit.second) else rest / unit.second
            rest -= n * unit.second
            parts += unit.first to n
        }
        val text = if (style == 0) {
            // Compact, like "1:30:00".
            parts.dropWhile { it.second == 0L && it != parts.last() }
                .mapIndexed { i, (_, n) -> if (i == 0) n.toString() else n.toString().padStart(2, '0') }
                .joinToString(":")
        } else {
            val shown = parts.filter { it.second != 0L }.ifEmpty { listOf(parts.last()) }
            shown.joinToString(" ") { (unit, n) ->
                if (style == 2) "$n ${LONG_NAMES[unit]}${if (n == 1L) "" else "s"}" else "$n${SHORT_NAMES[unit]}"
            }
        }
        return if (negative) "-$text" else text
    }

    private fun decimal(value: BigDecimal, places: Int?, grouping: Boolean): String {
        if (places == null) {
            val abs = value.abs()
            if (abs.signum() != 0 && (abs >= BigDecimal("1e15") || abs < BigDecimal("1e-9"))) return scientific(value, 6)
            val rounded = value.round(MathContext(15)).stripTrailingZeros()
            val df = DecimalFormat("0", symbols).apply {
                maximumFractionDigits = 15
                isGroupingUsed = grouping
                if (grouping) groupingSize = 3
            }
            return df.format(rounded)
        }
        val df = DecimalFormat("0", symbols).apply {
            minimumFractionDigits = places
            maximumFractionDigits = places
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = grouping
            if (grouping) groupingSize = 3
        }
        return df.format(value)
    }

    private fun currency(value: BigDecimal, code: String?, places: Int?, grouping: Boolean): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        val currency = try {
            code?.let { Currency.getInstance(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
        if (currency != null) nf.currency = currency
        val digits = places ?: (currency ?: nf.currency)?.defaultFractionDigits?.takeIf { it >= 0 } ?: 2
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        nf.isGroupingUsed = grouping
        (nf as? DecimalFormat)?.roundingMode = RoundingMode.HALF_UP
        return nf.format(value)
    }

    private fun scientific(value: BigDecimal, places: Int): String {
        val pattern = if (places > 0) "0." + "0".repeat(places) + "E00" else "0E00"
        return DecimalFormat(pattern, symbols).format(value).replace("E", "E+").replace("E+-", "E-")
    }

    companion object {
        const val DECIMAL = 256
        const val CURRENCY = 257
        const val PERCENT = 258
        const val SCIENTIFIC = 259
        const val TEXT = 260
        const val FRACTION = 262
        const val CHECKBOX = 263
        const val RATING = 267
        const val BASE = 269
        const val CUSTOM_NUMBER = 270
        const val CUSTOM_CURRENCY = 274

        const val WEEK = 1
        const val DAY = 2
        const val HOUR = 4
        const val MINUTE = 8
        const val SECOND = 16

        private val SHORT_NAMES = mapOf(WEEK to "w", DAY to "d", HOUR to "h", MINUTE to "m", SECOND to "s")
        private val LONG_NAMES = mapOf(WEEK to "week", DAY to "day", HOUR to "hour", MINUTE to "minute", SECOND to "second")

        const val CHECKED = "☑"
        const val UNCHECKED = "☐"
        const val STAR = "★"
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
