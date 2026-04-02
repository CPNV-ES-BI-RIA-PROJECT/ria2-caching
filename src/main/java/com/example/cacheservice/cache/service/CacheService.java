package com.example.cacheservice.cache.service;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.example.cacheservice.cache.config.CacheOperationProperties;
import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;
import com.example.cacheservice.cache.store.CacheStore;

@Service
public class CacheService {

    private final CacheStore cacheStore;
    private final CacheOperationProperties operationProperties;

    public CacheService(CacheStore cacheStore, CacheOperationProperties operationProperties) {
        this.cacheStore = cacheStore;
        this.operationProperties = operationProperties;
    }

    public CacheLookup lookup(String namespace, String key) {
        CacheLookup lookup = cacheStore.lookup(namespace, key);
        if (lookup.state() != com.example.cacheservice.cache.domain.CacheState.MISS) {
            return lookup;
        }

        LockAcquisition acquisition = cacheStore.acquireLock(
                namespace,
                key,
                Duration.ofMillis(operationProperties.getDefaultLeaseMs()));

        if (acquisition.acquired()) {
            return CacheLookup.miss();
        }

        return cacheStore.lookup(namespace, key);
    }

    public LockAcquisition acquireLock(String namespace, String key) {
        LockAcquisition acquisition = cacheStore.acquireLock(
                namespace,
                key,
                Duration.ofMillis(operationProperties.getDefaultLeaseMs()));
        if (!acquisition.acquired()) {
            throw new CacheConflictException("A cache entry is already ready or currently being computed.");
        }
        return acquisition;
    }

    public CacheEntry publish(String namespace, String key) {
        long effectiveTtlSeconds = operationProperties.getDefaultTtlSeconds();
        PublishOutcome outcome = cacheStore.publish(namespace, key,
                Duration.ofSeconds(effectiveTtlSeconds));

        return switch (outcome.type()) {
            case PUBLISHED -> outcome.entry();
            case LOCK_NOT_FOUND ->
                throw new LockExpiredException("The lock no longer exists. The computation must be retried.");
        };
    }

    public void delete(String namespace, String key) {
        cacheStore.delete(namespace, key);
    }

    public void deleteAll() {
        cacheStore.deleteAll();
    }
}
