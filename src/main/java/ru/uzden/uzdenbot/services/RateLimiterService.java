package ru.uzden.uzdenbot.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class RateLimiterService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final Duration window;
    private final long maxRequests;

    public RateLimiterService(
            StringRedisTemplate redis,
            @Value("${app.rate-limit.window-seconds:3}") long windowSeconds,
            @Value("${app.rate-limit.max-requests:3}") long maxRequests) {
        this.redis = redis;
        this.window = Duration.ofSeconds(windowSeconds);
        this.maxRequests = maxRequests;
        this.script = new DefaultRedisScript<>();
        this.script.setResultType(Long.class);
        this.script.setScriptText(
                "local current = redis.call('INCR', KEYS[1]); " +
                        "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; " +
                        "return current;"
        );
    }

    public boolean allow(String key) {
        Long count = redis.execute(
                script,
                List.of(key),
                String.valueOf(window.toMillis())
        );
        return count != null && count <= maxRequests;
    }
}
