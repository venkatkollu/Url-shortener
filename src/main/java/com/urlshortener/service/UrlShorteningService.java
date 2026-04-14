package com.urlshortener.service;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.entity.UrlMapping;
import com.urlshortener.exception.CustomAliasExistsException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShorteningService {

    private final UrlMappingRepository repository;
    private final Base62Encoder base62Encoder;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX = "url:";

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.cache.default-ttl-hours}")
    private long defaultCacheTtlHours;

    @Transactional
    public ShortenResponse shortenUrl(ShortenRequest request, String ipAddress) {
        String shortCode;

        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            shortCode = request.getCustomAlias();
            if (repository.existsByShortCode(shortCode)) {
                throw new CustomAliasExistsException(shortCode);
            }
        } else {
            shortCode = null; // will be set after save
        }

        LocalDateTime expiresAt = null;
        if (request.getExpiresInMinutes() != null && request.getExpiresInMinutes() > 0) {
            expiresAt = LocalDateTime.now().plusMinutes(request.getExpiresInMinutes());
        }

        UrlMapping mapping = UrlMapping.builder()
                .shortCode(shortCode != null ? shortCode : "TEMP")
                .originalUrl(request.getUrl())
                .expiresAt(expiresAt)
                .ipAddress(ipAddress)
                .build();

        mapping = repository.save(mapping);

        if (shortCode == null) {
            shortCode = base62Encoder.encode(mapping.getId());
            mapping.setShortCode(shortCode);
            mapping = repository.save(mapping);
        }

        cacheUrl(shortCode, mapping.getOriginalUrl(), expiresAt);

        log.info("Shortened URL: {} -> {}", shortCode, mapping.getOriginalUrl());

        return ShortenResponse.builder()
                .shortCode(shortCode)
                .shortUrl(baseUrl + "/" + shortCode)
                .originalUrl(mapping.getOriginalUrl())
                .createdAt(mapping.getCreatedAt())
                .expiresAt(mapping.getExpiresAt())
                .build();
    }

    @Transactional
    public String resolveUrl(String shortCode) {
        String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + shortCode);
        if (cached != null) {
            log.debug("Cache hit for short code: {}", shortCode);
            repository.incrementClickCount(shortCode);
            return cached;
        }

        log.debug("Cache miss for short code: {}", shortCode);

        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (mapping.isExpired()) {
            evictCache(shortCode);
            throw new UrlExpiredException(shortCode);
        }

        repository.incrementClickCount(shortCode);
        cacheUrl(shortCode, mapping.getOriginalUrl(), mapping.getExpiresAt());

        return mapping.getOriginalUrl();
    }

    @Transactional(readOnly = true)
    public UrlStatsResponse getUrlStats(String shortCode) {
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        return UrlStatsResponse.builder()
                .shortCode(mapping.getShortCode())
                .shortUrl(baseUrl + "/" + mapping.getShortCode())
                .originalUrl(mapping.getOriginalUrl())
                .createdAt(mapping.getCreatedAt())
                .expiresAt(mapping.getExpiresAt())
                .clickCount(mapping.getClickCount())
                .build();
    }

    @Transactional
    public void deleteUrl(String shortCode) {
        if (!repository.existsByShortCode(shortCode)) {
            throw new UrlNotFoundException(shortCode);
        }
        repository.deleteByShortCode(shortCode);
        evictCache(shortCode);
        log.info("Deleted short URL: {}", shortCode);
    }

    private void cacheUrl(String shortCode, String originalUrl, LocalDateTime expiresAt) {
        long ttlSeconds = TimeUnit.HOURS.toSeconds(defaultCacheTtlHours);
        if (expiresAt != null) {
            long secondsUntilExpiry = Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
            if (secondsUntilExpiry <= 0) return;
            ttlSeconds = Math.min(ttlSeconds, secondsUntilExpiry);
        }
        redisTemplate.opsForValue().set(
                CACHE_PREFIX + shortCode,
                originalUrl,
                ttlSeconds,
                TimeUnit.SECONDS
        );
    }

    private void evictCache(String shortCode) {
        redisTemplate.delete(CACHE_PREFIX + shortCode);
    }
}
