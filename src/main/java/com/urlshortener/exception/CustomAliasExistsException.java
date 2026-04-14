package com.urlshortener.exception;

public class CustomAliasExistsException extends RuntimeException {

    public CustomAliasExistsException(String alias) {
        super("Custom alias already taken: " + alias);
    }
}
