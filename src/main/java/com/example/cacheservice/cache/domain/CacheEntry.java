package com.example.cacheservice.cache.domain;

import java.time.Instant;

public record CacheEntry(
        String namespace,
        String key,
        CacheState status,
        Instant updatedAt) {
}
