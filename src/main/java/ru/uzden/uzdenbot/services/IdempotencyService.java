package ru.uzden.uzdenbot.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotencyService {

    private final StringRedisTemplate redis;
    private final Duration defaultTtl;

    public IdempotencyService(
            StringRedisTemplate redis,
            @Value("${app.idempotency.ttl-seconds:10}") long ttlSeconds) {
        this.redis = redis;
        this.defaultTtl = Duration.ofSeconds(ttlSeconds);
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, defaultTtl);
    }

    public boolean tryAcquire(String key, Duration ttl) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(key, Long.toString(System.currentTimeMillis()), ttl);
        return Boolean.TRUE.equals(ok);
    }
}
