package com.aidecision.backend.dto;

public record SimilarRecord(
        String id,
        String snippet,
        Double score
) {}
