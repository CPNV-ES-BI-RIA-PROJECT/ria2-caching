package com.example.cacheservice.cache.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.CacheState;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;
import com.example.cacheservice.cache.domain.PublishOutcomeType;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "cache.store.type", havingValue = "in-memory")
public class InMemoryCacheStore implements CacheStore {

    private final Map<String, StoredEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, StoredLock> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCacheStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized CacheLookup lookup(String namespace, String key) {
        String compositeKey = compositeKey(namespace, key);
        cleanup(compositeKey);

        StoredEntry entry = entries.get(compositeKey);
        if (entry == null) {
            return CacheLookup.miss();
        }

        CacheEntry cacheEntry = entry.toCacheEntry(namespace, key);
        return switch (cacheEntry.status()) {
            case READY -> CacheLookup.ready(cacheEntry);
            case COMPUTING -> CacheLookup.computing(cacheEntry);
            case MISS -> CacheLookup.miss();
        };
    }

    @Override
    public synchronized LockAcquisition acquireLock(String namespace, String key, String owner,
            Duration leaseDuration) {
        String compositeKey = compositeKey(namespace, key);
        cleanup(compositeKey);

        StoredEntry existingEntry = entries.get(compositeKey);
        if (existingEntry != null && existingEntry.status == CacheState.READY) {
            return new LockAcquisition(false, null, owner, leaseDuration, null);
        }

        if (locks.containsKey(compositeKey)) {
            return new LockAcquisition(false, null, owner, leaseDuration, null);
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(leaseDuration);
        String token = UUID.randomUUID().toString();

        locks.put(compositeKey, new StoredLock(token, owner, expiresAt));
        entries.put(compositeKey, new StoredEntry(CacheState.COMPUTING, null, null, now, owner, expiresAt));

        return new LockAcquisition(true, token, owner, leaseDuration, expiresAt);
    }

    @Override
    public synchronized PublishOutcome publish(
            String namespace,
            String key,
            String token,
            String artifactUri,
            JsonNode metadata,
            Duration ttl) {
        String compositeKey = compositeKey(namespace, key);
        cleanup(compositeKey);

        StoredLock lock = locks.get(compositeKey);
        if (lock == null) {
            return new PublishOutcome(PublishOutcomeType.LOCK_NOT_FOUND, null);
        }

        if (!lock.token.equals(token)) {
            return new PublishOutcome(PublishOutcomeType.INVALID_TOKEN, null);
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(ttl);
        StoredEntry entry = new StoredEntry(CacheState.READY, artifactUri, metadata, now, null, expiresAt);
        entries.put(compositeKey, entry);
        locks.remove(compositeKey);

        return new PublishOutcome(PublishOutcomeType.PUBLISHED, entry.toCacheEntry(namespace, key));
    }

    @Override
    public synchronized void delete(String namespace, String key) {
        String compositeKey = compositeKey(namespace, key);
        entries.remove(compositeKey);
        locks.remove(compositeKey);
    }

    private void cleanup(String compositeKey) {
        Instant now = Instant.now(clock);

        StoredLock lock = locks.get(compositeKey);
        if (lock != null && !lock.expiresAt.isAfter(now)) {
            locks.remove(compositeKey);
        }

        StoredEntry entry = entries.get(compositeKey);
        if (entry != null && !entry.expiresAt.isAfter(now)) {
            entries.remove(compositeKey);
        }
    }

    private String compositeKey(String namespace, String key) {
        return namespace + ":" + key;
    }

    private record StoredEntry(
            CacheState status,
            String artifactUri,
            JsonNode metadata,
            Instant updatedAt,
            String owner,
            Instant expiresAt) {

        private CacheEntry toCacheEntry(String namespace, String key) {
            return new CacheEntry(namespace, key, status, artifactUri, metadata, updatedAt, owner);
        }
    }

    private record StoredLock(String token, String owner, Instant expiresAt) {
    }
}
