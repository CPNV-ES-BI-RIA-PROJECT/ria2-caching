package com.example.cacheservice.cache.domain;

import java.time.Duration;
import java.time.Instant;

public record LockAcquisition(
        boolean acquired,
        String token,
        String owner,
        Duration leaseDuration,
        Instant expiresAt) {
}
