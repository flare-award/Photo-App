package com.flareaward.serendip.domain.scheduler

import com.flareaward.serendip.domain.model.AutoPhotoSettings
import com.flareaward.serendip.domain.model.PauseReason
import com.flareaward.serendip.domain.model.ScheduleMode
import com.flareaward.serendip.domain.model.SchedulerState
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSchedulerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val config = SchedulerConfig()
    private val scheduler = CaptureScheduler(random = Random(42), config = config)

    private fun at(date: LocalDate, time: LocalTime, z: ZoneId = zone): Long =
        date.atTime(time).atZone(z).toInstant().toEpochMilli()

    private val day: LocalDate = LocalDate.of(2026, 10, 8)
    private val morning = at(day, LocalTime.of(9, 0))

    private val randomSettings = AutoPhotoSettings(
        enabled = true,
        dailyPhotoLimit = 5,
        scheduleMode = ScheduleMode.FULLY_RANDOM,
    )
    private val intervalSettings = AutoPhotoSettings(
        enabled = true,
        dailyPhotoLimit = 5,
        scheduleMode = ScheduleMode.RANDOM_INTERVAL,
        minIntervalMinutes = 30,
        maxIntervalMinutes = 60,
    )

    private fun minutes(n: Int): Long = n * 60_000L

    // ---------------------------------------------------------------- planning

    @Test
    fun `fully random plan spreads the daily limit over the rest of the day`() {
        val rec = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone)
        val s = rec.state

        assertEquals(ReplanReason.INITIAL, rec.replanReason)
        assertEquals(day, s.dayKey)
        assertEquals(zone.id, s.zoneId)
        assertEquals(randomSettings.planSignature, s.planSignature)
        assertEquals(5, s.plannedSlots.size)
        assertEquals(s.plannedSlots.first(), s.nextCaptureAt)
        assertEquals(s.plannedSlots, s.plannedSlots.sorted())
        assertEquals(s.plannedSlots.size, s.plannedSlots.distinct().size)
        assertTrue(s.plannedSlots.first() >= morning + config.minLeadMs)
        assertTrue(s.plannedSlots.last() <= CaptureScheduler.startOfNextDay(morning, zone) - config.dayEndMarginMs)
        s.plannedSlots.zipWithNext { a, b -> assertTrue("gap too small", b - a >= config.preferredGapMs) }
    }

    @Test
    fun `plans are random but always respect the minimum gap`() {
        repeat(200) { seed ->
            val sch = CaptureScheduler(Random(seed), config)
            val slots = sch.sampleMoments(0L, minutes(16 * 60), 20)
            assertEquals(20, slots.size)
            slots.zipWithNext { a, b -> assertTrue(b - a >= config.preferredGapMs) }
            assertTrue(slots.first() >= 0L && slots.last() <= minutes(16 * 60))
        }
    }

    @Test
    fun `a short window holds fewer moments instead of clustering them`() {
        val slots = scheduler.sampleMoments(0L, minutes(30), 20)
        assertTrue(slots.size in 1..7)
        slots.zipWithNext { a, b -> assertTrue(b - a >= config.absoluteGapMs) }

        assertTrue(scheduler.sampleMoments(10L, 10L, 3).isEmpty())
        assertTrue(scheduler.sampleMoments(0L, minutes(60), 0).isEmpty())
    }

    @Test
    fun `reconcile is idempotent and never regenerates without a reason`() {
        val first = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val again = scheduler.reconcile(randomSettings, first, morning + minutes(1), zone)
        assertNull(again.replanReason)
        assertEquals(first, again.state)
        assertEquals(0, again.missedSlots)
    }

    // ---------------------------------------------------------------- due / success

    @Test
    fun `isDue honours the grace period and the in-progress claim`() {
        val s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val slot = s.nextCaptureAt!!

        assertFalse(scheduler.isDue(s, slot - 1))
        assertTrue(scheduler.isDue(s, slot))
        assertTrue(scheduler.isDue(s, slot + config.lateGraceMs))
        assertFalse(scheduler.isDue(s, slot + config.lateGraceMs + 1))
        assertFalse(scheduler.isDue(scheduler.claim(s, slot), slot))
    }

    @Test
    fun `success advances the plan and stops at the daily limit`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val originalSlots = s.plannedSlots
        var taken = 0
        while (true) {
            val slot = s.nextCaptureAt ?: break
            s = scheduler.claim(s, slot)
            s = scheduler.onAttemptFinished(randomSettings, s, slot + 1_000, zone, AttemptResult.Success)
            taken++
            assertEquals(taken, s.photosTakenToday)
            assertNull(s.captureInProgressSince)
            s.nextCaptureAt?.let { next ->
                assertTrue("plan is followed, not regenerated", originalSlots.contains(next))
            }
        }
        assertEquals(5, taken)
        assertTrue(s.plannedSlots.isEmpty())

        // Nothing more today: wake up right after midnight for the day rollover.
        val wake = scheduler.nextWakeAt(randomSettings, s, s.lastSuccessAt!!, zone)
        assertEquals(CaptureScheduler.startOfNextDay(morning, zone) + config.midnightWakeDelayMs, wake)

        // Still no regeneration later the same day.
        val later = scheduler.reconcile(randomSettings, s, at(day, LocalTime.of(23, 30)), zone)
        assertNull(later.replanReason)
        assertNull(later.state.nextCaptureAt)
    }

    @Test
    fun `the day rollover resets the counter and builds a new plan`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        s = s.copy(photosTakenToday = 5, plannedSlots = emptyList(), nextCaptureAt = null)

        val nextDay = at(day.plusDays(1), LocalTime.of(0, 1))
        val rec = scheduler.reconcile(randomSettings, s, nextDay, zone)

        assertTrue(rec.dayChanged)
        assertEquals(ReplanReason.NEW_DAY, rec.replanReason)
        assertEquals(0, rec.state.photosTakenToday)
        assertEquals(day.plusDays(1), rec.state.dayKey)
        assertEquals(5, rec.state.plannedSlots.size)
        assertTrue(rec.state.plannedSlots.all { it > nextDay })
    }

    // ---------------------------------------------------------------- missed moments

    @Test
    fun `missed moments are forfeited in fully random mode and the rest of the plan is kept`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val slots = s.plannedSlots
        // Device was off until shortly after the second moment passed the grace period.
        val now = slots[1] + config.lateGraceMs + 1

        val rec = scheduler.reconcile(randomSettings, s, now, zone)
        s = rec.state

        assertEquals(2, rec.missedSlots)
        assertEquals(ReplanReason.MISSED, rec.replanReason)
        assertEquals(slots.drop(2), s.plannedSlots)
        assertEquals(slots[2], s.nextCaptureAt)
        assertEquals(0, s.photosTakenToday)

        // A second look reports nothing new.
        val again = scheduler.reconcile(randomSettings, s, now + 1, zone)
        assertEquals(0, again.missedSlots)
        assertNull(again.replanReason)
    }

    @Test
    fun `when every moment of the day was missed nothing is caught up`() {
        val planned = listOf(LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(18, 0)).map { at(day, it) }
        val s = SchedulerState(
            dayKey = day,
            zoneId = zone.id,
            planSignature = randomSettings.copy(dailyPhotoLimit = 3).planSignature,
            plannedSlots = planned,
            nextCaptureAt = planned.first(),
        )
        val evening = at(day, LocalTime.of(18, 30))
        val rec = scheduler.reconcile(randomSettings.copy(dailyPhotoLimit = 3), s, evening, zone)

        assertEquals(3, rec.missedSlots)
        assertEquals(ReplanReason.MISSED, rec.replanReason)
        assertNull(rec.state.nextCaptureAt)
        assertTrue(rec.state.plannedSlots.isEmpty())
        assertEquals(0, rec.state.photosTakenToday)
        val wake = scheduler.nextWakeAt(randomSettings.copy(dailyPhotoLimit = 3), rec.state, evening, zone)
        assertEquals(CaptureScheduler.startOfNextDay(evening, zone) + config.midnightWakeDelayMs, wake)
    }

    @Test
    fun `a missed moment in interval mode starts a new interval from now`() {
        val s = scheduler.reconcile(intervalSettings, SchedulerState.EMPTY, morning, zone).state
        val next = s.nextCaptureAt!!
        assertTrue(next in morning + minutes(30)..morning + minutes(60))

        val now = next + config.lateGraceMs + minutes(5)
        val rec = scheduler.reconcile(intervalSettings, s, now, zone)
        assertEquals(1, rec.missedSlots)
        assertEquals(ReplanReason.MISSED, rec.replanReason)
        assertTrue(rec.state.nextCaptureAt!! in now + minutes(30)..now + minutes(60))
    }

    // ---------------------------------------------------------------- interval mode

    @Test
    fun `interval mode draws a fresh interval after every success and respects the limit`() {
        var s = scheduler.reconcile(intervalSettings, SchedulerState.EMPTY, morning, zone).state
        var now = morning
        val intervals = mutableListOf<Long>()
        repeat(5) {
            val slot = s.nextCaptureAt!!
            intervals += slot - now
            now = slot
            s = scheduler.onAttemptFinished(intervalSettings, scheduler.claim(s, now), now, zone, AttemptResult.Success)
        }
        assertEquals(5, s.photosTakenToday)
        assertNull(s.nextCaptureAt)
        assertTrue(intervals.all { it in minutes(30)..minutes(60) })
        assertTrue("intervals vary", intervals.distinct().size > 1)
    }

    @Test
    fun `interval mode carries a pending moment across midnight`() {
        val lateNight = at(day, LocalTime.of(23, 50))
        val s = scheduler.reconcile(intervalSettings, SchedulerState.EMPTY, lateNight, zone).state
        val pending = s.nextCaptureAt!!
        assertTrue(pending > CaptureScheduler.startOfNextDay(lateNight, zone))

        // The midnight wake-up comes first ...
        val wake = scheduler.nextWakeAt(intervalSettings, s, lateNight, zone)!!
        assertEquals(CaptureScheduler.startOfNextDay(lateNight, zone) + config.midnightWakeDelayMs, wake)

        // ... and only rolls the day, keeping the pending moment.
        val rec = scheduler.reconcile(intervalSettings, s, wake, zone)
        assertTrue(rec.dayChanged)
        assertNull(rec.replanReason)
        assertEquals(pending, rec.state.nextCaptureAt)
        assertEquals(0, rec.state.photosTakenToday)
    }

    // ---------------------------------------------------------------- settings / zone / time changes

    @Test
    fun `changing the limit rebuilds the plan for the remaining photos only`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val slot = s.nextCaptureAt!!
        s = scheduler.onAttemptFinished(randomSettings, scheduler.claim(s, slot), slot, zone, AttemptResult.Success)
        assertEquals(1, s.photosTakenToday)

        val bigger = randomSettings.copy(dailyPhotoLimit = 10)
        val rec = scheduler.reconcile(bigger, s, slot + minutes(1), zone)
        assertEquals(ReplanReason.SETTINGS_CHANGED, rec.replanReason)
        assertEquals(1, rec.state.photosTakenToday)
        assertEquals(9, rec.state.plannedSlots.size)

        val smaller = randomSettings.copy(dailyPhotoLimit = 1)
        val rec2 = scheduler.reconcile(smaller, rec.state, slot + minutes(2), zone)
        assertEquals(ReplanReason.SETTINGS_CHANGED, rec2.replanReason)
        assertTrue(rec2.state.plannedSlots.isEmpty())
        assertNull(rec2.state.nextCaptureAt)
    }

    @Test
    fun `switching the mode rebuilds the plan`() {
        val s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val rec = scheduler.reconcile(intervalSettings, s, morning + minutes(1), zone)
        assertEquals(ReplanReason.SETTINGS_CHANGED, rec.replanReason)
        assertTrue(rec.state.plannedSlots.isEmpty())
        assertTrue(rec.state.nextCaptureAt!! >= morning + minutes(1) + minutes(30))
    }

    @Test
    fun `a time zone change keeps the counter and rebuilds the plan in the new zone`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        s = s.copy(photosTakenToday = 2)
        val berlin = ZoneId.of("Europe/Berlin")
        val rec = scheduler.reconcile(randomSettings, s, morning + minutes(10), berlin)
        assertEquals(ReplanReason.ZONE_CHANGED, rec.replanReason)
        assertEquals(berlin.id, rec.state.zoneId)
        assertEquals(2, rec.state.photosTakenToday)
        assertEquals(3, rec.state.plannedSlots.size)
        assertTrue(rec.state.plannedSlots.last() <= CaptureScheduler.startOfNextDay(morning, berlin))
    }

    @Test
    fun `a clock that jumped backwards invalidates a far-future plan`() {
        val s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val twoDaysEarlier = morning - 2 * 24 * 60 * 60_000L
        val rec = scheduler.reconcile(randomSettings, s, twoDaysEarlier, zone)
        // The local date differs, so the day rollover handles it; either way the plan is fresh.
        assertNotNull(rec.replanReason)
        assertTrue(rec.state.plannedSlots.all { it > twoDaysEarlier })
    }

    // ---------------------------------------------------------------- skip / failures / pause

    @Test
    fun `a screen-off skip is not counted and the next moment is at least 30 minutes away`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val slot = s.nextCaptureAt!!
        s = scheduler.onAttemptFinished(randomSettings, scheduler.claim(s, slot), slot, zone, AttemptResult.SkippedScreenOff)
        assertEquals(0, s.photosTakenToday)
        assertEquals(5, s.plannedSlots.size)
        assertTrue(s.nextCaptureAt!! >= slot + config.skipRetryLeadMs)

        var i = scheduler.reconcile(intervalSettings, SchedulerState.EMPTY, morning, zone).state
        val islot = i.nextCaptureAt!!
        i = scheduler.onAttemptFinished(intervalSettings, scheduler.claim(i, islot), islot, zone, AttemptResult.SkippedScreenOff)
        assertTrue(i.nextCaptureAt!! >= islot + config.skipRetryLeadMs)
    }

    @Test
    fun `transient failures retry a few times and then give the moment up`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        var now = s.nextCaptureAt!!
        repeat(config.maxQuickRetries) { attempt ->
            s = scheduler.onAttemptFinished(randomSettings, scheduler.claim(s, now), now, zone, AttemptResult.RetryableFailure)
            assertEquals(attempt + 1, s.consecutiveFailures)
            val next = s.nextCaptureAt!!
            assertTrue(next - now in config.failureRetryMinMs..config.failureRetryMaxMs || s.plannedSlots.contains(next))
            now = next
        }
        s = scheduler.onAttemptFinished(randomSettings, scheduler.claim(s, now), now, zone, AttemptResult.RetryableFailure)
        assertEquals(0, s.consecutiveFailures)
        assertTrue(s.nextCaptureAt!! >= now + config.skipRetryLeadMs)
        assertEquals(0, s.photosTakenToday)
    }

    @Test
    fun `a blocking failure pauses everything until resumed`() {
        var s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val slot = s.nextCaptureAt!!
        s = scheduler.onAttemptFinished(
            randomSettings, scheduler.claim(s, slot), slot, zone,
            AttemptResult.Blocking(PauseReason.CAMERA_ACCESS_BLOCKED),
        )
        assertEquals(PauseReason.CAMERA_ACCESS_BLOCKED, s.pauseReason)
        assertNull(s.nextCaptureAt)
        assertTrue(s.plannedSlots.isEmpty())
        assertNull(scheduler.nextWakeAt(randomSettings, s, slot, zone))

        val resumed = scheduler.reconcile(randomSettings, scheduler.resume(s), slot + minutes(5), zone, minLeadMs = minutes(1))
        assertNull(resumed.state.pauseReason)
        assertEquals(ReplanReason.INITIAL, resumed.replanReason)
        assertEquals(5, resumed.state.plannedSlots.size)
        assertTrue(resumed.state.nextCaptureAt!! >= slot + minutes(5) + config.minLeadMs)
    }

    @Test
    fun `a user-initiated start defers a moment that is due right now`() {
        val s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val slot = s.nextCaptureAt!!
        val rec = scheduler.reconcile(randomSettings, s, slot + 10_000, zone, minLeadMs = minutes(1))
        assertEquals(ReplanReason.DEFERRED, rec.replanReason)
        assertEquals(0, rec.missedSlots)
        assertFalse(scheduler.isDue(rec.state, slot + 10_000))
        assertTrue(rec.state.nextCaptureAt!! >= slot + 10_000 + config.minLeadMs)
        assertEquals(rec.state.plannedSlots.size, rec.state.plannedSlots.distinct().size)
    }

    @Test
    fun `a stale in-progress claim from a dead process is cleared`() {
        val s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        val claimed = scheduler.claim(s, morning + minutes(5))
        val rec = scheduler.reconcile(randomSettings, claimed, morning + minutes(5) + config.staleClaimMs + 1, zone)
        assertNull(rec.state.captureInProgressSince)
        assertNull(rec.replanReason)
    }

    @Test
    fun `nextWakeAt never sleeps past the day rollover and is null when disabled`() {
        val s = scheduler.reconcile(randomSettings, SchedulerState.EMPTY, morning, zone).state
        assertEquals(s.nextCaptureAt, scheduler.nextWakeAt(randomSettings, s, morning, zone))
        assertNull(scheduler.nextWakeAt(randomSettings.copy(enabled = false), s, morning, zone))

        val idle = s.copy(nextCaptureAt = null, plannedSlots = emptyList())
        val midnight = CaptureScheduler.startOfNextDay(morning, zone) + config.midnightWakeDelayMs
        assertEquals(midnight, scheduler.nextWakeAt(randomSettings, idle, morning, zone))
    }

    @Test
    fun `settings are sanitized into valid ranges`() {
        val s = AutoPhotoSettings(dailyPhotoLimit = 999, minIntervalMinutes = 400, maxIntervalMinutes = 10).sanitized()
        assertEquals(AutoPhotoSettings.MAX_DAILY_LIMIT, s.dailyPhotoLimit)
        assertTrue(s.minIntervalMinutes <= s.maxIntervalMinutes)
        assertTrue(s.isIntervalRangeValid)
    }
}
