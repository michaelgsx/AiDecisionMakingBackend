package com.aidecision.backend.dto;

/**
 * Five-part analyst write-up from the assess chat model (structured LLM JSON {@code reasoning} object).
 */
public record AiAssessReasoning(
        String retrievalAndScores,
        String featureComparison,
        String narrativeAlignment,
        String historicalDecisions,
        String synthesis
) {
    /** Plain-text rendering for {@link AssessResponse#aiReason()} backward compatibility. */
    public String toFormattedText() {
        return """
                1) Retrieval & scores
                %s

                2) Feature-by-feature comparison
                %s

                3) Narrative alignment
                %s

                4) Historical decisions
                %s

                5) Synthesis
                %s"""
                .formatted(
                        nullToEmpty(retrievalAndScores),
                        nullToEmpty(featureComparison),
                        nullToEmpty(narrativeAlignment),
                        nullToEmpty(historicalDecisions),
                        nullToEmpty(synthesis))
                .trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
