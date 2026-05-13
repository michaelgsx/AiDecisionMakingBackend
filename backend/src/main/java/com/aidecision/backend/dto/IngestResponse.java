package com.aidecision.backend.dto;

public record IngestResponse(
        boolean ok,
        Long recordIndex,
        String recordId,
        String message
) {}
