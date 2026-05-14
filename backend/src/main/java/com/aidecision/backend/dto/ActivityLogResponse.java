package com.aidecision.backend.dto;

public record ActivityLogResponse(
        boolean ok,
        Long id,
        String hash,
        String prevHash,
        String message
) {}
