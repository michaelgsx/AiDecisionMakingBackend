package com.aidecision.backend.dto;

/**
 * One citeable fact supporting the assess label (from current case or a similar indexed row).
 */
public record AiAssessEvidenceItem(
        /** similar_case | current_feature | narrative */
        String kind,
        String recordId,
        Double similarityScore,
        String reviewOutcome,
        String field,
        String value,
        /** Short claim tying this fact to the decision. */
        String claim,
        /** Verbatim or paraphrased excerpt from the user message. */
        String quote,
        /** Which label this item supports: passed | rejected | frozen */
        String supportsLabel
) {}
