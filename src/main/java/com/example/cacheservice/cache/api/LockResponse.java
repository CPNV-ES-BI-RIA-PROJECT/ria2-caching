package com.example.cacheservice.cache.api;

import java.time.Instant;

import com.example.cacheservice.cache.domain.LockAcquisition;

public record LockResponse(
        String token,
        String owner,
        long leaseMs,
        Instant expiresAt) {

    public static LockResponse from(LockAcquisition acquisition) {
        return new LockResponse(
                acquisition.token(),
                acquisition.owner(),
                acquisition.leaseDuration().toMillis(),
                acquisition.expiresAt());
    }
}
