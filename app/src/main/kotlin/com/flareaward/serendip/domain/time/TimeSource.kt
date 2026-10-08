package com.flareaward.serendip.domain.time

import java.time.ZoneId

/** Wall-clock access, abstracted so the scheduler can be tested deterministically. */
interface TimeSource {
    /** Current epoch millis (wall clock, follows user time changes). */
    fun nowMillis(): Long

    /** Current device time zone. */
    fun zone(): ZoneId
}
