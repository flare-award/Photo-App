package com.flareaward.serendip.domain.model

/** How the moments for automatic photos are chosen. */
enum class ScheduleMode {
    /** N photos are spread over the (remaining) calendar day at random, non-clustered moments. */
    FULLY_RANDOM,

    /** After every attempt a new random interval in [min, max] minutes is drawn. */
    RANDOM_INTERVAL,
}

/**
 * User settings of the automatic photo mode. Persisted in DataStore; the only
 * place in the app where these values are defined.
 */
data class AutoPhotoSettings(
    val enabled: Boolean = false,
    val dailyPhotoLimit: Int = DEFAULT_DAILY_LIMIT,
    val scheduleMode: ScheduleMode = ScheduleMode.FULLY_RANDOM,
    val minIntervalMinutes: Int = DEFAULT_MIN_INTERVAL_MINUTES,
    val maxIntervalMinutes: Int = DEFAULT_MAX_INTERVAL_MINUTES,
    val notifyOnSuccess: Boolean = true,
    val skipWhenScreenOff: Boolean = false,
) {
    /**
     * The part of the settings that defines *when* photos are taken. When this
     * changes while the mode is active the scheduler rebuilds its plan.
     */
    val planSignature: String
        get() = "$dailyPhotoLimit|${scheduleMode.name}|$minIntervalMinutes|$maxIntervalMinutes"

    val isIntervalRangeValid: Boolean
        get() = minIntervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES &&
            maxIntervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES &&
            minIntervalMinutes <= maxIntervalMinutes

    /** Returns a copy with every value clamped into its valid range (min <= max guaranteed). */
    fun sanitized(): AutoPhotoSettings {
        val limit = dailyPhotoLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)
        val min = minIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        val max = maxIntervalMinutes.coerceIn(min, MAX_INTERVAL_MINUTES)
        return copy(dailyPhotoLimit = limit, minIntervalMinutes = min, maxIntervalMinutes = max)
    }

    companion object {
        const val DEFAULT_DAILY_LIMIT = 3
        const val MIN_DAILY_LIMIT = 1
        const val MAX_DAILY_LIMIT = 20
        val DAILY_LIMIT_PRESETS: List<Int> = listOf(1, 2, 3, 5, 10, 20)

        const val MIN_INTERVAL_MINUTES = 5
        const val MAX_INTERVAL_MINUTES = 12 * 60
        const val DEFAULT_MIN_INTERVAL_MINUTES = 30
        const val DEFAULT_MAX_INTERVAL_MINUTES = 120

        /** Discrete values offered by the interval controls (minutes). */
        val INTERVAL_PRESETS_MINUTES: List<Int> =
            listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720)
    }
}
