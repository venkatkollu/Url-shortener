package com.urlshortener.service;

import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.dto.ShortenResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.entity.UrlMapping;
import com.urlshortener.exception.CustomAliasExistsException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShorteningServiceTest {

    @Mock
    private UrlMappingRepository repository;

    @Mock
    private Base62Encoder base62Encoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UrlShorteningService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "defaultCacheTtlHours", 24L);
    }

    @Test
    @DisplayName("Shorten URL generates Base62 code when no custom alias")
    void shortenUrl_generatesBase62Code() {
        ShortenRequest request = new ShortenRequest("https://example.com", null, null);

        UrlMapping savedMapping = UrlMapping.builder()
                .id(1L)
                .shortCode("TEMP")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .build();

        UrlMapping updatedMapping = UrlMapping.builder()
                .id(1L)
                .shortCode("1")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.save(any(UrlMapping.class)))
                .thenReturn(savedMapping)
                .thenReturn(updatedMapping);
        when(base62Encoder.encode(1L)).thenReturn("1");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ShortenResponse response = service.shortenUrl(request, "127.0.0.1");

        assertNotNull(response);
        assertEquals("1", response.getShortCode());
        assertEquals("http://localhost:8081/1", response.getShortUrl());
        assertEquals("https://example.com", response.getOriginalUrl());
    }

    @Test
    @DisplayName("Shorten URL with custom alias uses the alias as short code")
    void shortenUrl_withCustomAlias() {
        ShortenRequest request = new ShortenRequest("https://example.com", "my-link", null);

        UrlMapping savedMapping = UrlMapping.builder()
                .id(1L)
                .shortCode("my-link")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.existsByShortCode("my-link")).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ShortenResponse response = service.shortenUrl(request, "127.0.0.1");

        assertEquals("my-link", response.getShortCode());
    }

    @Test
    @DisplayName("Shorten URL with short expiry caches only until actual expiry")
    void shortenUrl_shortExpiry_setsSecondsTtl() {
        ShortenRequest request = new ShortenRequest("https://example.com", "expiring", 10L);

        UrlMapping savedMapping = UrlMapping.builder()
                .id(1L)
                .shortCode("expiring")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(repository.existsByShortCode("expiring")).thenReturn(false);
        when(repository.save(any(UrlMapping.class))).thenReturn(savedMapping);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.shortenUrl(request, "127.0.0.1");

        verify(valueOperations).set(
                eq("url:expiring"),
                eq("https://example.com"),
                longThat(ttl -> ttl > 0 && ttl <= 600),
                eq(TimeUnit.SECONDS)
        );
    }

    @Test
    @DisplayName("Shorten URL with duplicate alias throws exception")
    void shortenUrl_duplicateAlias_throwsException() {
        ShortenRequest request = new ShortenRequest("https://example.com", "taken", null);
        when(repository.existsByShortCode("taken")).thenReturn(true);

        assertThrows(CustomAliasExistsException.class,
                () -> service.shortenUrl(request, "127.0.0.1"));
    }

    @Test
    @DisplayName("Resolve URL returns original URL from cache on hit")
    void resolveUrl_cacheHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:abc")).thenReturn("https://example.com");

        String result = service.resolveUrl("abc");

        assertEquals("https://example.com", result);
        verify(repository, never()).findByShortCode(anyString());
        verify(repository).incrementClickCount("abc");
    }

    @Test
    @DisplayName("Resolve URL queries DB on cache miss and populates cache")
    void resolveUrl_cacheMiss() {
        UrlMapping mapping = UrlMapping.builder()
                .shortCode("abc")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:abc")).thenReturn(null);
        when(repository.findByShortCode("abc")).thenReturn(Optional.of(mapping));

        String result = service.resolveUrl("abc");

        assertEquals("https://example.com", result);
        verify(repository).incrementClickCount("abc");
        verify(valueOperations).set(eq("url:abc"), eq("https://example.com"), anyLong(), any());
    }

    @Test
    @DisplayName("Resolve URL throws UrlNotFoundException for missing code")
    void resolveUrl_notFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:missing")).thenReturn(null);
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> service.resolveUrl("missing"));
    }

    @Test
    @DisplayName("Resolve URL throws UrlExpiredException for expired URL")
    void resolveUrl_expired() {
        UrlMapping mapping = UrlMapping.builder()
                .shortCode("old")
                .originalUrl("https://example.com")
                .expiresAt(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:old")).thenReturn(null);
        when(repository.findByShortCode("old")).thenReturn(Optional.of(mapping));

        assertThrows(UrlExpiredException.class, () -> service.resolveUrl("old"));
    }

    @Test
    @DisplayName("Get URL stats returns correct response")
    void getUrlStats() {
        UrlMapping mapping = UrlMapping.builder()
                .shortCode("abc")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .clickCount(42L)
                .build();

        when(repository.findByShortCode("abc")).thenReturn(Optional.of(mapping));

        UrlStatsResponse stats = service.getUrlStats("abc");

        assertEquals("abc", stats.getShortCode());
        assertEquals(42L, stats.getClickCount());
        assertEquals("https://example.com", stats.getOriginalUrl());
    }

    @Test
    @DisplayName("Delete URL removes from DB and cache")
    void deleteUrl() {
        when(repository.existsByShortCode("abc")).thenReturn(true);

        service.deleteUrl("abc");

        verify(repository).deleteByShortCode("abc");
        verify(redisTemplate).delete("url:abc");
    }

    @Test
    @DisplayName("Delete non-existent URL throws UrlNotFoundException")
    void deleteUrl_notFound() {
        when(repository.existsByShortCode("missing")).thenReturn(false);

        assertThrows(UrlNotFoundException.class, () -> service.deleteUrl("missing"));
    }
}
