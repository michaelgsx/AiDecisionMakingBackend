package com.aidecision.backend.dto;

import java.util.List;

public record AssessResponse(
        String risk,
        /** Short Azure AI Search summary only (not the LLM write-up). */
        String reason,
        List<SimilarRecord> similarRecords,
        /** Model label: passed | rejected | frozen; null if chat step skipped or failed. */
        String aiLabel,
        /** Full analyst write-up as plain text; null if chat step skipped or failed. */
        String aiReason,
        /** Model self-reported confidence in [0, 1]; null if omitted or chat skipped. */
        Double aiConfidence,
        /** Top risk drivers cited by the model; empty if omitted or chat skipped. */
        List<String> aiKeyRiskFactors,
        /** Structured five-part reasoning; null if chat step skipped or failed. */
        AiAssessReasoning aiReasoning,
        /** Citeable facts from current case and similar rows; null if chat step skipped or failed. */
        AiAssessEvidence aiEvidence
) {
    public AssessResponse(
            String risk,
            String reason,
            List<SimilarRecord> similarRecords,
            String aiLabel,
            String aiReason) {
        this(risk, reason, similarRecords, aiLabel, aiReason, null, List.of(), null, null);
    }
}
