package com.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.ShortenRequest;
import com.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UrlControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("urlshortener_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UrlMappingRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/v1/shorten creates a short URL")
    void createShortUrl() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com", null, null);

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.shortUrl").isNotEmpty())
                .andExpect(jsonPath("$.originalUrl").value("https://www.google.com"));
    }

    @Test
    @DisplayName("POST /api/v1/shorten with custom alias uses the alias")
    void createShortUrlWithCustomAlias() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com", "google", null);

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("google"));
    }

    @Test
    @DisplayName("POST /api/v1/shorten with duplicate alias returns 409")
    void duplicateAliasReturns409() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com", "dup", null);

        mockMvc.perform(post("/api/v1/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /{shortCode} redirects to original URL")
    void redirectToOriginalUrl() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com", "redir", null);

        mockMvc.perform(post("/api/v1/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        mockMvc.perform(get("/redir"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://www.google.com"));
    }

    @Test
    @DisplayName("GET /{shortCode} for non-existent code returns 404")
    void redirectNotFound() throws Exception {
        mockMvc.perform(get("/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/urls/{shortCode}/stats returns click stats")
    void getUrlStats() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com", "stats-test", null);

        mockMvc.perform(post("/api/v1/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // click once
        mockMvc.perform(get("/stats-test"));

        mockMvc.perform(get("/api/v1/urls/stats-test/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(1))
                .andExpect(jsonPath("$.originalUrl").value("https://www.google.com"));
    }

    @Test
    @DisplayName("DELETE /api/v1/urls/{shortCode} removes URL")
    void deleteUrl() throws Exception {
        ShortenRequest request = new ShortenRequest("https://www.google.com", "del-test", null);

        mockMvc.perform(post("/api/v1/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        mockMvc.perform(delete("/api/v1/urls/del-test"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/del-test"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/shorten with invalid URL returns 400")
    void invalidUrlReturns400() throws Exception {
        ShortenRequest request = new ShortenRequest("not-a-url", null, null);

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
