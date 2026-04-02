package com.example.cacheservice.cache.store;

import java.time.Duration;

import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;

public interface CacheStore {

    CacheLookup lookup(String namespace, String key);

    LockAcquisition acquireLock(String namespace, String key, Duration leaseDuration);

    PublishOutcome publish(String namespace, String key, Duration ttl);

    void delete(String namespace, String key);

    void deleteAll();
}
