package com.aidecision.backend.dto;

import java.util.List;

/**
 * Structured evidence backing the assess label (LLM JSON {@code evidence} object).
 */
public record AiAssessEvidence(
        String summary,
        List<AiAssessEvidenceItem> items
) {
    public static AiAssessEvidence empty() {
        return new AiAssessEvidence("", List.of());
    }
}
