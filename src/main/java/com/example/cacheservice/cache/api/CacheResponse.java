package com.example.cacheservice.cache.api;

import java.time.Instant;

import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheState;

public record CacheResponse(
        String namespace,
        String key,
        CacheState status,
        Instant updatedAt) {

    public static CacheResponse from(CacheEntry entry) {
        return new CacheResponse(
                entry.namespace(),
                entry.key(),
                entry.status(),
                entry.updatedAt());
    }

    public static CacheResponse miss(String namespace, String key) {
        return new CacheResponse(namespace, key, CacheState.MISS, null);
    }
}
