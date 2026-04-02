package com.example.cacheservice.cache.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.CacheState;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;
import com.example.cacheservice.cache.domain.PublishOutcomeType;

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
    public synchronized LockAcquisition acquireLock(String namespace, String key, Duration leaseDuration) {
        String compositeKey = compositeKey(namespace, key);
        cleanup(compositeKey);

        StoredEntry existingEntry = entries.get(compositeKey);
        if (existingEntry != null && existingEntry.status == CacheState.READY) {
            return new LockAcquisition(false, leaseDuration, null);
        }

        if (locks.containsKey(compositeKey)) {
            return new LockAcquisition(false, leaseDuration, null);
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(leaseDuration);

        locks.put(compositeKey, new StoredLock(expiresAt));
        entries.put(compositeKey, new StoredEntry(CacheState.COMPUTING, now, expiresAt));

        return new LockAcquisition(true, leaseDuration, expiresAt);
    }

    @Override
    public synchronized PublishOutcome publish(
            String namespace,
            String key,
            Duration ttl) {
        String compositeKey = compositeKey(namespace, key);
        cleanup(compositeKey);

        StoredLock lock = locks.get(compositeKey);
        if (lock == null) {
            return new PublishOutcome(PublishOutcomeType.LOCK_NOT_FOUND, null);
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(ttl);
        StoredEntry entry = new StoredEntry(CacheState.READY, now, expiresAt);
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

    @Override
    public synchronized void deleteAll() {
        entries.clear();
        locks.clear();
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
            Instant updatedAt,
            Instant expiresAt) {

        private CacheEntry toCacheEntry(String namespace, String key) {
            return new CacheEntry(namespace, key, status, updatedAt);
        }
    }

    private record StoredLock(Instant expiresAt) {
    }
}
