package com.goldenpaw

import com.goldenpaw.data.remote.SupabaseSyncRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncOverlapTest {
    @Test
    fun cursorMovesBackByTheOverlapWindow() {
        assertEquals("2026-10-13T09:59:30Z", SupabaseSyncRepository.overlapCursor("2026-10-13T10:00:00Z"))
        // PostgREST returns offsets and microseconds; both survive.
        assertEquals("2026-10-13T09:59:30.123456Z", SupabaseSyncRepository.overlapCursor("2026-10-13T10:00:00.123456+00:00"))
        assertEquals("2026-10-13T04:29:30Z", SupabaseSyncRepository.overlapCursor("2026-10-13T10:00:00+05:30"))
    }

    @Test
    fun anUnreadableCursorIsKept() {
        assertEquals("not-a-time", SupabaseSyncRepository.overlapCursor("not-a-time"))
    }
}
