package com.example.cacheservice.cache.service;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;
import com.example.cacheservice.cache.store.CacheStore;
import tools.jackson.databind.JsonNode;

@Service
public class CacheService {

    private final CacheStore cacheStore;

    public CacheService(CacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    public CacheLookup lookup(String namespace, String key) {
        return cacheStore.lookup(namespace, key);
    }

    public LockAcquisition acquireLock(String namespace, String key, String owner, long leaseMs) {
        LockAcquisition acquisition = cacheStore.acquireLock(namespace, key, owner, Duration.ofMillis(leaseMs));
        if (!acquisition.acquired()) {
            throw new CacheConflictException("A cache entry is already ready or currently being computed.");
        }
        return acquisition;
    }

    public CacheEntry publish(String namespace, String key, String token, String artifactUri, JsonNode metadata,
            long ttlSeconds) {
        PublishOutcome outcome = cacheStore.publish(namespace, key, token, artifactUri, metadata,
                Duration.ofSeconds(ttlSeconds));

        return switch (outcome.type()) {
            case PUBLISHED -> outcome.entry();
            case LOCK_NOT_FOUND ->
                throw new LockExpiredException("The lock no longer exists. The computation must be retried.");
            case INVALID_TOKEN ->
                throw new InvalidLockTokenException("The provided lock token is invalid for this cache entry.");
        };
    }

    public void delete(String namespace, String key) {
        cacheStore.delete(namespace, key);
    }
}
