package com.aidecision.backend.dto;

import java.time.Instant;

public record ActivityLogEntry(
        Long id,
        String userId,
        String transactionId,
        String bizAction,
        String recordAction,
        String prevHash,
        String hash,
        Instant createdAt
) {}
