package com.gouge.guaili.data

import org.junit.Assert.*
import org.junit.Test

class SnapshotDecodeCacheTest {
    @Test fun equalPayloadsAndMalformedPayloadsAreDecodedOnlyOnce() {
        val cache = SnapshotDecodeCache<Any>()
        var parses = 0
        val decode: (String) -> Any = { parses++; check(it != "broken"); Any() }
        val first = cache.decode("payload", decode)
        assertSame(first, cache.decode("payload", decode))
        assertEquals(1, parses)
        assertNull(cache.decode("broken", decode))
        assertNull(cache.decode("broken", decode))
        assertEquals(2, parses)
        assertNull(cache.decode(null, decode))
        assertEquals(2, parses)
    }

    @Test fun onlyStoresWithTheSameBackingIdentityShareAParsedValue() {
        val registry = SnapshotCacheRegistry<Any>()
        val firstStore = Any()
        val secondStore = Any()
        assertSame(registry.forStore(firstStore), registry.forStore(firstStore))
        assertNotSame(registry.forStore(firstStore), registry.forStore(secondStore))
        val saved = Any()
        registry.forStore(firstStore).remember("saved", saved)
        assertSame(saved, registry.forStore(firstStore).decode("saved") { error("already parsed") })
    }
}
