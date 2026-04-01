package com.example.cacheservice.cache.domain;

public record CacheLookup(CacheState state, CacheEntry entry) {

    public static CacheLookup miss() {
        return new CacheLookup(CacheState.MISS, null);
    }

    public static CacheLookup computing(CacheEntry entry) {
        return new CacheLookup(CacheState.COMPUTING, entry);
    }

    public static CacheLookup ready(CacheEntry entry) {
        return new CacheLookup(CacheState.READY, entry);
    }
}
