package com.gouge.guaili.data

import java.util.WeakHashMap

/** One parsed value per DataStore, shared by observers and short-lived store instances. */
internal class SnapshotDecodeCache<T> {
    private var initialized = false
    private var encoded: String? = null
    private var value: T? = null

    @Synchronized
    fun decode(source: String?, decoder: (String) -> T): T? {
        if (!initialized || encoded != source) {
            value = source?.let { runCatching { decoder(it) }.getOrNull() }
            encoded = source
            initialized = true
        }
        return value
    }

    @Synchronized
    fun remember(source: String, decoded: T) {
        encoded = source
        value = decoded
        initialized = true
    }
}

/** Weak keys do not keep a test store or an unused backing file alive. */
internal class SnapshotCacheRegistry<T> {
    private val entries = WeakHashMap<Any, SnapshotDecodeCache<T>>()

    @Synchronized
    fun forStore(store: Any): SnapshotDecodeCache<T> =
        entries.getOrPut(store) { SnapshotDecodeCache() }
}
