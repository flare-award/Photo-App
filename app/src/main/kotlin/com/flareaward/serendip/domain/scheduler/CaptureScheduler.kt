package com.flareaward.serendip.domain.scheduler

import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.model.ScheduleMode
import com.flareaward.serendip.domain.model.SchedulerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Tunables of the scheduler. Durations are milliseconds. */
data class SchedulerConfig(
    /** A moment that is older than this is "missed" and never executed. Covers Doze / alarm batching delays. */
    val lateGraceMs: Long = 20 * MINUTE,
    /** The earliest a freshly planned moment may be after "now". */
    val minLeadMs: Long = 2 * MINUTE,
    /** Preferred minimum distance between two planned moments. */
    val preferredGapMs: Long = 20 * MINUTE,
    /** Hard minimum distance between two planned moments when the day is almost over. */
    val absoluteGapMs: Long = 5 * MINUTE,
    /** Earliest retry after a screen-off skip — prevents a burst right after the screen turns on. */
    val skipRetryLeadMs: Long = 30 * MINUTE,
    val failureRetryMinMs: Long = 5 * MINUTE,
    val failureRetryMaxMs: Long = 15 * MINUTE,
    /** Quick retries after a transient failure before the moment is given up. */
    val maxQuickRetries: Int = 3,
    /** Nothing is planned within the last minute of a day. */
    val dayEndMarginMs: Long = MINUTE,
    /** How long after local midnight the day-rollover wake-up is placed. */
    val midnightWakeDelayMs: Long = MINUTE,
    /** An in-progress claim older than this belongs to a process that died mid-capture. */
    val staleClaimMs: Long = 3 * MINUTE,
) {
    companion object {
        const val MINUTE = 60_000L
    }
}

/** Why the plan was rebuilt. Useful for logging / diagnostics. */
enum class ReplanReason { INITIAL, NEW_DAY, ZONE_CHANGED, SETTINGS_CHANGED, MISSED, TIME_CHANGED, DEFERRED }

/** Outcome of [CaptureScheduler.reconcile]. */
data class Reconciliation(
    val state: SchedulerState,
    /** Planned moments that passed without being executed. Reported once, never caught up. */
    val missedSlots: Int,
    val dayChanged: Boolean,
    val replanReason: ReplanReason?,
)

/** What happened at a capture moment, as far as the scheduler is concerned. */
sealed interface AttemptResult {
    data object Success : AttemptResult

    data object SkippedScreenOff : AttemptResult

    /** A transient failure: the moment may be retried shortly. */
    data object RetryableFailure : AttemptResult

    /** A failure that needs the user (or the service) before anything can be captured again. */
    data class Blocking(val pauseReason: PauseReason) : AttemptResult
}

/**
 * Pure, deterministic (given [random]) scheduling logic. It never touches
 * Android APIs or storage: the engine loads [SchedulerState], asks this class
 * what to do and persists the result. All timestamps are epoch milliseconds,
 * all "day" math is done in the supplied [ZoneId].
 */
class CaptureScheduler(
    private val random: Random = Random.Default,
    val config: SchedulerConfig = SchedulerConfig(),
) {

    /**
     * Brings a persisted state in line with the current moment and settings:
     * day rollover, zone change, settings change, missed moments, stale claims
     * and clock jumps. Safe to call as often as needed — the plan is only
     * rebuilt when there is a real reason to (missed moments are *forfeited*,
     * never caught up, and never cause a rebuild in FULLY_RANDOM mode).
     *
     * @param minLeadMs when started by the user (opening the app, flipping the
     * switch) a small lead prevents a capture in the very next seconds; a
     * moment that is due right now is then deferred instead of executed.
     */
    fun reconcile(
        settings: AutoPhotoSettings,
        state: SchedulerState,
        nowMillis: Long,
        zone: ZoneId,
        minLeadMs: Long = 0L,
    ): Reconciliation {
        val today = localDate(nowMillis, zone)
        var s = state
        var reason: ReplanReason? = null
        var rebuild = false
        var dayChanged = false

        // 1. Moments that passed without being executed. Counted once, before anything changes.
        val missedThreshold = nowMillis - config.lateGraceMs
        val overdue = (s.plannedSlots + listOfNotNull(s.nextCaptureAt)).distinct().count { it < missedThreshold }

        // 2. A claim left behind by a process that died while capturing.
        s.captureInProgressSince?.let { since ->
            if (nowMillis - since > config.staleClaimMs || since > nowMillis) s = s.copy(captureInProgressSince = null)
        }

        // 3. New calendar day (or first run): counters reset. In RANDOM_INTERVAL mode a
        //    pending moment is carried over so the chain of intervals is not broken.
        if (s.dayKey == null || s.dayKey != today) {
            dayChanged = s.dayKey != null
            val pending = s.nextCaptureAt
            val carryOver = settings.scheduleMode == ScheduleMode.RANDOM_INTERVAL &&
                pending != null && pending > nowMillis && overdue == 0 &&
                s.planSignature == settings.planSignature && s.zoneId == zone.id
            s = s.copy(
                dayKey = today,
                zoneId = zone.id,
                photosTakenToday = 0,
                plannedSlots = emptyList(),
                nextCaptureAt = if (carryOver) pending else null,
                consecutiveFailures = 0,
            )
            if (!carryOver) {
                reason = if (dayChanged) ReplanReason.NEW_DAY else ReplanReason.INITIAL
                rebuild = true
            }
        } else if (s.zoneId != zone.id) {
            s = s.copy(zoneId = zone.id)
            reason = ReplanReason.ZONE_CHANGED
            rebuild = true
        }

        // 4. No plan for the current settings yet (first enable, resume after a pause, settings changed).
        if (s.planSignature != settings.planSignature) {
            if (!rebuild) {
                reason = if (s.planSignature == null) ReplanReason.INITIAL else ReplanReason.SETTINGS_CHANGED
                rebuild = true
            }
            s = s.copy(planSignature = settings.planSignature)
        }

        if (!rebuild) {
            val next = s.nextCaptureAt
            if (next != null && next > startOfNextDay(nowMillis, zone) + DAY_MS) {
                // 5. Clock moved backwards (or an impossible value was restored).
                reason = ReplanReason.TIME_CHANGED
                rebuild = true
            } else if (overdue > 0) {
                // 6. Missed moments are forfeited. FULLY_RANDOM keeps the rest of its plan untouched;
                //    RANDOM_INTERVAL simply starts a new interval from now.
                reason = ReplanReason.MISSED
                when (settings.scheduleMode) {
                    ScheduleMode.FULLY_RANDOM -> {
                        val kept = s.plannedSlots.filter { it >= missedThreshold }.sorted()
                        s = s.copy(plannedSlots = kept, nextCaptureAt = kept.firstOrNull())
                    }

                    ScheduleMode.RANDOM_INTERVAL -> rebuild = true
                }
            } else if (next != null && minLeadMs > 0 && next < nowMillis + minLeadMs) {
                // 7. User-initiated start while a moment is due: push it a little instead of shooting now.
                reason = ReplanReason.DEFERRED
                s = defer(settings, s, nowMillis, zone, minLeadMs)
            }
        }

        if (rebuild) {
            s = plan(settings, s, nowMillis, zone, leadMs = max(minLeadMs, config.minLeadMs))
        }
        return Reconciliation(state = s, missedSlots = overdue, dayChanged = dayChanged, replanReason = reason)
    }

    private fun defer(
        settings: AutoPhotoSettings,
        state: SchedulerState,
        nowMillis: Long,
        zone: ZoneId,
        minLeadMs: Long,
    ): SchedulerState {
        val shifted = nowMillis + max(minLeadMs, config.minLeadMs) + randomBetween(0L, 3 * SchedulerConfig.MINUTE)
        val dayEnd = lastMomentOfDay(nowMillis, zone)
        return when (settings.scheduleMode) {
            ScheduleMode.RANDOM_INTERVAL -> state.copy(nextCaptureAt = shifted)

            ScheduleMode.FULLY_RANDOM -> {
                val others = state.plannedSlots.filter { it != state.nextCaptureAt && it > nowMillis }
                val following = others.firstOrNull()
                val keepDeferred = shifted <= dayEnd && (following == null || following >= shifted + config.absoluteGapMs)
                val slots = if (keepDeferred) (others + shifted).sorted() else others
                state.copy(plannedSlots = slots, nextCaptureAt = slots.firstOrNull())
            }
        }
    }

    /** `true` when the next planned moment is now (or late by at most the grace period) and nothing is in progress. */
    fun isDue(state: SchedulerState, nowMillis: Long): Boolean {
        val next = state.nextCaptureAt ?: return false
        if (state.captureInProgressSince != null) return false
        return nowMillis >= next && nowMillis - next <= config.lateGraceMs
    }

    /** Marks the current moment as being executed. Persist the result before capturing. */
    fun claim(state: SchedulerState, nowMillis: Long): SchedulerState =
        state.copy(captureInProgressSince = nowMillis)

    /** Applies the outcome of the attempt that was claimed with [claim] and plans what follows. */
    fun onAttemptFinished(
        settings: AutoPhotoSettings,
        state: SchedulerState,
        nowMillis: Long,
        zone: ZoneId,
        result: AttemptResult,
    ): SchedulerState {
        val remainingSlots = state.plannedSlots.filter { it > nowMillis }
        var s = state.copy(
            lastAttemptAt = nowMillis,
            captureInProgressSince = null,
            plannedSlots = remainingSlots,
        )
        return when (result) {
            AttemptResult.Success -> {
                s = s.copy(
                    photosTakenToday = s.photosTakenToday + 1,
                    lastSuccessAt = nowMillis,
                    consecutiveFailures = 0,
                )
                when {
                    s.photosTakenToday >= settings.dailyPhotoLimit ->
                        s.copy(plannedSlots = emptyList(), nextCaptureAt = null)

                    settings.scheduleMode == ScheduleMode.RANDOM_INTERVAL ->
                        s.copy(nextCaptureAt = nowMillis + randomInterval(settings))

                    else -> continueExistingPlan(settings, s, nowMillis, zone)
                }
            }

            AttemptResult.SkippedScreenOff -> {
                s = s.copy(consecutiveFailures = 0)
                when (settings.scheduleMode) {
                    ScheduleMode.RANDOM_INTERVAL ->
                        s.copy(nextCaptureAt = nowMillis + max(config.skipRetryLeadMs, randomInterval(settings)))

                    ScheduleMode.FULLY_RANDOM ->
                        plan(settings, s, nowMillis, zone, leadMs = config.skipRetryLeadMs)
                }
            }

            AttemptResult.RetryableFailure -> {
                val failures = s.consecutiveFailures + 1
                s = s.copy(consecutiveFailures = failures)
                if (failures <= config.maxQuickRetries) {
                    val retryAt = nowMillis + randomBetween(config.failureRetryMinMs, config.failureRetryMaxMs)
                    val nextPlanned = remainingSlots.firstOrNull()
                    val next = if (nextPlanned != null && nextPlanned <= retryAt) nextPlanned else retryAt
                    s.copy(nextCaptureAt = min(next, lastMomentOfDay(nowMillis, zone)).takeIf { it > nowMillis })
                } else {
                    s = s.copy(consecutiveFailures = 0)
                    when (settings.scheduleMode) {
                        ScheduleMode.RANDOM_INTERVAL -> s.copy(nextCaptureAt = nowMillis + randomInterval(settings))
                        ScheduleMode.FULLY_RANDOM -> plan(settings, s, nowMillis, zone, leadMs = config.skipRetryLeadMs)
                    }
                }
            }

            is AttemptResult.Blocking ->
                s.copy(
                    pauseReason = result.pauseReason,
                    plannedSlots = emptyList(),
                    nextCaptureAt = null,
                    consecutiveFailures = 0,
                )
        }
    }

    /** Pauses without an attempt (e.g. the service could not start). Nothing stays planned. */
    fun pause(state: SchedulerState, reason: PauseReason): SchedulerState =
        state.copy(pauseReason = reason, plannedSlots = emptyList(), nextCaptureAt = null, captureInProgressSince = null)

    /** Clears the pause; the next [reconcile] builds a fresh plan. */
    fun resume(state: SchedulerState): SchedulerState = state.copy(pauseReason = null, planSignature = null)

    /**
     * When the engine must wake up next: the next capture moment, but never
     * later than shortly after local midnight (the day counter has to roll
     * over even when nothing is planned). `null` = no wake-up needed.
     */
    fun nextWakeAt(settings: AutoPhotoSettings, state: SchedulerState, nowMillis: Long, zone: ZoneId): Long? {
        if (!settings.enabled || state.pauseReason != null) return null
        val midnight = startOfNextDay(nowMillis, zone) + config.midnightWakeDelayMs
        val next = state.nextCaptureAt ?: return midnight
        return min(max(next, nowMillis), midnight)
    }

    // ---------------------------------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------------------------------

    private fun plan(
        settings: AutoPhotoSettings,
        state: SchedulerState,
        nowMillis: Long,
        zone: ZoneId,
        leadMs: Long,
    ): SchedulerState {
        val remaining = settings.dailyPhotoLimit - state.photosTakenToday
        if (remaining <= 0) return state.copy(plannedSlots = emptyList(), nextCaptureAt = null)
        return when (settings.scheduleMode) {
            ScheduleMode.FULLY_RANDOM -> {
                val windowStart = nowMillis + leadMs
                val windowEnd = lastMomentOfDay(nowMillis, zone)
                val slots = sampleMoments(windowStart, windowEnd, remaining)
                state.copy(plannedSlots = slots, nextCaptureAt = slots.firstOrNull())
            }

            ScheduleMode.RANDOM_INTERVAL -> {
                val next = nowMillis + max(leadMs, randomInterval(settings))
                state.copy(plannedSlots = emptyList(), nextCaptureAt = next)
            }
        }
    }

    /**
     * After a success in FULLY_RANDOM mode the existing plan continues. If the
     * plan ran dry (short day) the rest is re-planned; a moment that ended up too
     * close to the capture that just happened is pushed away by the hard gap.
     */
    private fun continueExistingPlan(
        settings: AutoPhotoSettings,
        state: SchedulerState,
        nowMillis: Long,
        zone: ZoneId,
    ): SchedulerState {
        val slots = state.plannedSlots
        if (slots.isEmpty()) return plan(settings, state, nowMillis, zone, leadMs = config.preferredGapMs)
        val earliest = nowMillis + config.absoluteGapMs
        val dayEnd = lastMomentOfDay(nowMillis, zone)
        val adjusted = slots.map { max(it, earliest) }.filter { it <= dayEnd }.distinct()
        if (adjusted.isEmpty()) return state.copy(plannedSlots = emptyList(), nextCaptureAt = null)
        return state.copy(plannedSlots = adjusted, nextCaptureAt = adjusted.first())
    }

    /**
     * Draws [count] moments in `[windowStart, windowEnd]` that are at least a gap
     * apart. Uses the classic "sample in a shrunken window, then spread" trick,
     * which yields exactly the uniform distribution conditioned on the minimum
     * spacing — truly random, but never clustered. If the window cannot hold
     * [count] moments the number is reduced; the day is simply too short then.
     */
    internal fun sampleMoments(windowStart: Long, windowEnd: Long, count: Int): List<Long> {
        val width = windowEnd - windowStart
        if (width <= 0 || count <= 0) return emptyList()
        var n = count
        var gap = config.preferredGapMs
        if ((n - 1) * gap > width) {
            gap = max(config.absoluteGapMs, if (n > 1) width / (n - 1) else width)
            if ((n - 1) * gap > width) n = (width / gap).toInt() + 1
        }
        val free = width - (n - 1) * gap
        val points = List(n) { randomBetween(0L, free) }.sorted()
        return points.mapIndexed { index, p -> windowStart + p + index * gap }
    }

    private fun randomInterval(settings: AutoPhotoSettings): Long {
        val minMs = settings.minIntervalMinutes * SchedulerConfig.MINUTE
        val maxMs = settings.maxIntervalMinutes * SchedulerConfig.MINUTE
        return randomBetween(min(minMs, maxMs), max(minMs, maxMs))
    }

    /** Uniform in `[from, to]` (inclusive). */
    private fun randomBetween(from: Long, to: Long): Long =
        if (to <= from) from else from + random.nextLong(to - from + 1)

    private fun lastMomentOfDay(nowMillis: Long, zone: ZoneId): Long =
        startOfNextDay(nowMillis, zone) - config.dayEndMarginMs

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

        /** Epoch millis of 00:00 of the next local day (DST-safe). */
        fun startOfNextDay(epochMillis: Long, zone: ZoneId): Long =
            localDate(epochMillis, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        /** Epoch millis of 00:00 of the current local day. */
        fun startOfDay(epochMillis: Long, zone: ZoneId): Long =
            localDate(epochMillis, zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
