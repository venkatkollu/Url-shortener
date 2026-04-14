package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShortenRequest {

    @NotBlank(message = "URL is required")
    @URL(message = "Must be a valid URL")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    private String url;

    @Size(min = 3, max = 30, message = "Custom alias must be between 3 and 30 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "Alias can only contain letters, numbers, hyphens, and underscores")
    private String customAlias;

    private Long expiresInMinutes;
}
