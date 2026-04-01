package com.example.cacheservice.cache.domain;

import java.time.Duration;
import java.time.Instant;

public record LockAcquisition(
        boolean acquired,
        Duration leaseDuration,
        Instant expiresAt) {
}
