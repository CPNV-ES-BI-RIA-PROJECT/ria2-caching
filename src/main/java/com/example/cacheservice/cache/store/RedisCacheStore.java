package com.example.cacheservice.cache.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
                      'updatedAt', ARGV[3],
                      'owner', ARGV[4]
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

                    if currentToken ~= ARGV[1] then
                      return 1
                    end

                    redis.call('HSET', KEYS[1],
                      'status', 'READY',
                      'artifactUri', ARGV[2],
                      'metadata', ARGV[3],
                      'updatedAt', ARGV[4]
                    )
                    redis.call('HDEL', KEYS[1], 'owner')
                    redis.call('EXPIRE', KEYS[1], ARGV[5])
                    redis.call('DEL', KEYS[2])
                    return 2
                    """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final CacheRedisProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedisCacheStore(
            StringRedisTemplate redisTemplate,
            CacheRedisProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
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
            CacheEntry computingEntry = new CacheEntry(namespace, key, CacheState.COMPUTING, null, null, now, null);
            return CacheLookup.computing(computingEntry);
        }

        return CacheLookup.miss();
    }

    @Override
    public LockAcquisition acquireLock(String namespace, String key, String owner, Duration leaseDuration) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(leaseDuration);
        String token = UUID.randomUUID().toString();

        Long result = redisTemplate.execute(
                ACQUIRE_LOCK_SCRIPT,
                List.of(cacheKey(namespace, key), lockKey(namespace, key)),
                token,
                Long.toString(leaseDuration.toMillis()),
                now.toString(),
                owner);

        boolean acquired = Long.valueOf(1L).equals(result);
        return new LockAcquisition(acquired, acquired ? token : null, owner, leaseDuration,
                acquired ? expiresAt : null);
    }

    @Override
    public PublishOutcome publish(
            String namespace,
            String key,
            String token,
            String artifactUri,
            JsonNode metadata,
            Duration ttl) {
        Instant now = Instant.now(clock);
        String serializedMetadata = serializeMetadata(metadata);

        Long result = redisTemplate.execute(
                PUBLISH_SCRIPT,
                List.of(cacheKey(namespace, key), lockKey(namespace, key)),
                token,
                artifactUri,
                serializedMetadata,
                now.toString(),
                Long.toString(ttl.toSeconds()));

        if (Long.valueOf(0L).equals(result)) {
            return new PublishOutcome(PublishOutcomeType.LOCK_NOT_FOUND, null);
        }
        if (Long.valueOf(1L).equals(result)) {
            return new PublishOutcome(PublishOutcomeType.INVALID_TOKEN, null);
        }

        CacheEntry entry = new CacheEntry(namespace, key, CacheState.READY, artifactUri, metadata, now, null);
        return new PublishOutcome(PublishOutcomeType.PUBLISHED, entry);
    }

    @Override
    public void delete(String namespace, String key) {
        redisTemplate.delete(List.of(cacheKey(namespace, key), lockKey(namespace, key)));
    }

    private CacheEntry mapEntry(String namespace, String key, Map<Object, Object> rawEntry) {
        CacheState state = CacheState.valueOf(stringValue(rawEntry.get("status")));
        String artifactUri = stringValue(rawEntry.get("artifactUri"));
        String owner = stringValue(rawEntry.get("owner"));
        Instant updatedAt = Instant.parse(stringValue(rawEntry.get("updatedAt")));
        JsonNode metadata = deserializeMetadata(stringValue(rawEntry.get("metadata")));

        return new CacheEntry(namespace, key, state, artifactUri, metadata, updatedAt, owner);
    }

    private String cacheKey(String namespace, String key) {
        return properties.getKeyPrefix().getCache() + namespace + ":" + key;
    }

    private String lockKey(String namespace, String key) {
        return properties.getKeyPrefix().getLock() + namespace + ":" + key;
    }

    private String serializeMetadata(JsonNode metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize cache metadata", exception);
        }
    }

    private JsonNode deserializeMetadata(String metadata) {
        if (metadata == null || metadata.isBlank() || "null".equals(metadata)) {
            return null;
        }

        try {
            return objectMapper.readTree(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to deserialize cache metadata", exception);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
