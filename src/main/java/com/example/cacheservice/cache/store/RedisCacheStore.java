package com.example.cacheservice.cache.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.example.cacheservice.cache.config.CacheRedisProperties;
import com.example.cacheservice.cache.domain.CacheEntry;
import com.example.cacheservice.cache.domain.CacheLookup;
import com.example.cacheservice.cache.domain.CacheState;
import com.example.cacheservice.cache.domain.LockAcquisition;
import com.example.cacheservice.cache.domain.PublishOutcome;
import com.example.cacheservice.cache.domain.PublishOutcomeType;

@Component
@ConditionalOnProperty(name = "cache.store.type", havingValue = "redis", matchIfMissing = true)
public class RedisCacheStore implements CacheStore {

    private static final DefaultRedisScript<Long> ACQUIRE_LOCK_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('HGET', KEYS[1], 'status') == 'READY' then
                      return 0
                    end

                    local locked = redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2], 'NX')
                    if not locked then
                      return 0
                    end

                    redis.call('HSET', KEYS[1],
                      'status', 'COMPUTING',
                      'updatedAt', ARGV[3]
                    )
                    redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    return 1
                    """,
            Long.class);

    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = new DefaultRedisScript<>(
            """
                    local currentToken = redis.call('GET', KEYS[2])
                    if not currentToken then
                      return 0
                    end

                    redis.call('HSET', KEYS[1],
                      'status', 'READY',
                      'updatedAt', ARGV[1]
                    )
                    redis.call('EXPIRE', KEYS[1], ARGV[2])
                    redis.call('DEL', KEYS[2])
                    return 1
                    """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final CacheRedisProperties properties;
    private final Clock clock;

    public RedisCacheStore(
            StringRedisTemplate redisTemplate,
            CacheRedisProperties properties,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public CacheLookup lookup(String namespace, String key) {
        String cacheKey = cacheKey(namespace, key);
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        Map<Object, Object> rawEntry = hashOperations.entries(cacheKey);

        if (!rawEntry.isEmpty()) {
            CacheEntry entry = mapEntry(namespace, key, rawEntry);
            if (entry.status() == CacheState.READY) {
                return CacheLookup.ready(entry);
            }
            if (entry.status() == CacheState.COMPUTING) {
                return CacheLookup.computing(entry);
            }
        }

        String lockToken = redisTemplate.opsForValue().get(lockKey(namespace, key));
        if (lockToken != null) {
            Instant now = Instant.now(clock);
            CacheEntry computingEntry = new CacheEntry(namespace, key, CacheState.COMPUTING, now);
            return CacheLookup.computing(computingEntry);
        }

        return CacheLookup.miss();
    }

    @Override
    public LockAcquisition acquireLock(String namespace, String key, Duration leaseDuration) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(leaseDuration);

        Long result = redisTemplate.execute(
                ACQUIRE_LOCK_SCRIPT,
                List.of(cacheKey(namespace, key), lockKey(namespace, key)),
                "LOCKED",
                Long.toString(leaseDuration.toMillis()),
                now.toString());

        boolean acquired = Long.valueOf(1L).equals(result);
        return new LockAcquisition(acquired, leaseDuration, acquired ? expiresAt : null);
    }

    @Override
    public PublishOutcome publish(
            String namespace,
            String key,
            Duration ttl) {
        Instant now = Instant.now(clock);

        Long result = redisTemplate.execute(
                PUBLISH_SCRIPT,
                List.of(cacheKey(namespace, key), lockKey(namespace, key)),
                now.toString(),
                Long.toString(ttl.toSeconds()));

        if (Long.valueOf(0L).equals(result)) {
            return new PublishOutcome(PublishOutcomeType.LOCK_NOT_FOUND, null);
        }

        CacheEntry entry = new CacheEntry(namespace, key, CacheState.READY, now);
        return new PublishOutcome(PublishOutcomeType.PUBLISHED, entry);
    }

    @Override
    public void delete(String namespace, String key) {
        redisTemplate.delete(List.of(cacheKey(namespace, key), lockKey(namespace, key)));
    }

    private CacheEntry mapEntry(String namespace, String key, Map<Object, Object> rawEntry) {
        CacheState state = CacheState.valueOf(stringValue(rawEntry.get("status")));
        Instant updatedAt = Instant.parse(stringValue(rawEntry.get("updatedAt")));

        return new CacheEntry(namespace, key, state, updatedAt);
    }

    private String cacheKey(String namespace, String key) {
        return properties.getKeyPrefix().getCache() + namespace + ":" + key;
    }

    private String lockKey(String namespace, String key) {
        return properties.getKeyPrefix().getLock() + namespace + ":" + key;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
