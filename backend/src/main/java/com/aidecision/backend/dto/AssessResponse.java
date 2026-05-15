package com.aidecision.backend.dto;

import java.util.List;

public record AssessResponse(
        String risk,
        String reason,
        List<SimilarRecord> similarRecords,
        /** Model label: passed | rejected | frozen; null if chat step skipped or failed. */
        String aiLabel,
        /** Model rationale; null if chat step skipped or failed. */
        String aiReason
) {}
