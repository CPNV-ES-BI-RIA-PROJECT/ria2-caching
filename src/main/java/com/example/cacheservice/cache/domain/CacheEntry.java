package com.example.cacheservice.cache.domain;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record CacheEntry(
        String namespace,
        String key,
        CacheState status,
        String artifactUri,
        JsonNode metadata,
        Instant updatedAt,
        String owner) {
}
