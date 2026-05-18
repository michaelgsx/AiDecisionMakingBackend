package com.aidecision.backend.dto;

import java.util.List;

/**
 * Parsed assess chat completion: label, optional confidence / risk factors, and structured reasoning.
 */
public record AiAssessDecision(
        String label,
        Double confidence,
        List<String> keyRiskFactors,
        AiAssessReasoning reasoning,
        AiAssessEvidence evidence
) {
    public String formattedReason() {
        return reasoning == null ? "" : reasoning.toFormattedText();
    }
}
