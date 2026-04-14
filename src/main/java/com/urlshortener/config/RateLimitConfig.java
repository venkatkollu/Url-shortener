package com.urlshortener.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.rate-limit")
@Data
public class RateLimitConfig {

    private int requestsPerMinute = 100;
}
