package com.aidecision.backend.dto;

public record ErrorResponse(
        boolean ok,
        String message
) {
    public ErrorResponse(String message) {
        this(false, message);
    }
}
