package com.example.cacheservice.cache.store;

import java.time.Duration;

import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;
import tools.jackson.databind.JsonNode;

public interface CacheStore {

    CacheLookup lookup(String namespace, String key);

    LockAcquisition acquireLock(String namespace, String key, String owner, Duration leaseDuration);

    PublishOutcome publish(String namespace, String key, String token, String artifactUri, JsonNode metadata,
            Duration ttl);

    void delete(String namespace, String key);
}
