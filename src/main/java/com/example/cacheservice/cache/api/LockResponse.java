package com.example.cacheservice.cache.api;

import java.time.Instant;

import com.example.cacheservice.cache.domain.CacheState;
import com.example.cacheservice.cache.domain.LockAcquisition;

public record LockResponse(
        String namespace,
        String key,
        CacheState status,
        long leaseMs,
        Instant expiresAt) {

    public static LockResponse from(String namespace, String key, LockAcquisition acquisition) {
        return new LockResponse(
                namespace,
                key,
                CacheState.COMPUTING,
                acquisition.leaseDuration().toMillis(),
                acquisition.expiresAt());
    }
}
