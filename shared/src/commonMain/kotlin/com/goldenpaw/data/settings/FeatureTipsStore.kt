package com.goldenpaw.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remembers which one-time feature tips (coach marks) the owner has already seen, so each tip is
 * shown once, the first time its feature is on screen. "Show tips again" in Settings resets it.
 */
class FeatureTipsStore(private val store: KeyValues) {
    private val mutex = Mutex()

    val seen: Flow<Set<String>> = store.observe().map { decode(it[KEY]) }.distinctUntilChanged()

    suspend fun markSeen(id: String) = mutex.withLock {
        val current = decode(store.get(KEY))
        if (id !in current) store.put(KEY, encode(current + id))
    }

    suspend fun markAllSeen(ids: Collection<String>) = mutex.withLock {
        store.put(KEY, encode(decode(store.get(KEY)) + ids))
    }

    suspend fun reset() = mutex.withLock { store.put(KEY, null) }

    companion object {
        const val KEY = "tips.seen"

        fun decode(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        fun encode(ids: Set<String>): String = ids.sorted().joinToString(",")
    }
}
