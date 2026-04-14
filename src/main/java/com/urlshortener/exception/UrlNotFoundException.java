package com.urlshortener.exception;

public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("URL not found for short code: " + shortCode);
    }
}
