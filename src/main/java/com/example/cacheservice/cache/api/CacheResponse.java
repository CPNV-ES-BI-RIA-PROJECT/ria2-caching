package com.example.cacheservice.cache.api;

import java.time.Instant;

import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheState;
import tools.jackson.databind.JsonNode;

public record CacheResponse(
        String namespace,
        String key,
        CacheState status,
        String artifactUri,
        JsonNode metadata,
        Instant updatedAt,
        String owner) {

    public static CacheResponse from(CacheEntry entry) {
        return new CacheResponse(
                entry.namespace(),
                entry.key(),
                entry.status(),
                entry.artifactUri(),
                entry.metadata(),
                entry.updatedAt(),
                entry.owner());
    }

    public static CacheResponse miss(String namespace, String key) {
        return new CacheResponse(namespace, key, CacheState.MISS, null, null, null, null);
    }
}
