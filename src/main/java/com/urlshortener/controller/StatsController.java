package com.urlshortener.controller;

import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.service.UrlShorteningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
public class StatsController {

    private final UrlShorteningService urlShorteningService;

    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<UrlStatsResponse> getUrlStats(@PathVariable String shortCode) {
        UrlStatsResponse stats = urlShorteningService.getUrlStats(shortCode);
        return ResponseEntity.ok(stats);
    }
}
