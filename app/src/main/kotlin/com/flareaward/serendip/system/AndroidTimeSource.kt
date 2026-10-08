package com.flareaward.serendip.system

import com.flareaward.serendip.domain.time.TimeSource
import java.time.ZoneId

class AndroidTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
