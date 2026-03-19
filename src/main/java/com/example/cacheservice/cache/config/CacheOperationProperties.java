package com.example.cacheservice.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cache.operation")
public class CacheOperationProperties {

	private long defaultLeaseMs = 300000;
	private long defaultTtlSeconds = 86400;

	public long getDefaultLeaseMs() {
		return defaultLeaseMs;
	}

	public void setDefaultLeaseMs(long defaultLeaseMs) {
		this.defaultLeaseMs = defaultLeaseMs;
	}

	public long getDefaultTtlSeconds() {
		return defaultTtlSeconds;
	}

	public void setDefaultTtlSeconds(long defaultTtlSeconds) {
		this.defaultTtlSeconds = defaultTtlSeconds;
	}
}
