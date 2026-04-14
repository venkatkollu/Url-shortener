package com.urlshortener.service;

import com.urlshortener.config.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final RateLimitConfig rateLimitConfig;

    private static final String RATE_LIMIT_PREFIX = "rate_limit:";

    /**
     * Fixed-window rate limiter using Redis INCR + EXPIRE.
     * Returns true if the request is allowed, false if rate limit is exceeded.
     */
    public boolean isAllowed(String clientIp) {
        String key = RATE_LIMIT_PREFIX + clientIp;

        Long currentCount = redisTemplate.opsForValue().increment(key);
        if (currentCount == null) {
            return true;
        }

        if (currentCount == 1) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }

        boolean allowed = currentCount <= rateLimitConfig.getRequestsPerMinute();
        if (!allowed) {
            log.warn("Rate limit exceeded for IP: {} (count: {})", clientIp, currentCount);
        }
        return allowed;
    }
}
