package com.example.cacheservice.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cache.redis")
public class CacheRedisProperties {

	private final KeyPrefix keyPrefix = new KeyPrefix();

	public KeyPrefix getKeyPrefix() {
		return keyPrefix;
	}

	public static class KeyPrefix {

		private String cache = "c:";
		private String lock = "l:";

		public String getCache() {
			return cache;
		}

		public void setCache(String cache) {
			this.cache = cache;
		}

		public String getLock() {
			return lock;
		}

		public void setLock(String lock) {
			this.lock = lock;
		}
	}
}
