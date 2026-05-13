package com.aidecision.backend.dto;

import java.util.List;

public record AssessResponse(
        String risk,
        String reason,
        List<SimilarRecord> similarRecords
) {}
