package com.aidecision.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One similar row from Azure AI Search. {@code snippet} stays a short preview; other fields carry
 * full index payload when available; {@code readableText} is a fixed-layout summary for UI / logs.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SimilarRecord(
        String id,
        String snippet,
        Double score,
        String recordId,
        String reviewOutcome,
        String caseNotes,
        String metadataJson,
        String content,
        String userId,
        String scenario,
        String transactionId,
        String readableText
) {
    /** Minimal record (e.g. tests): only id, snippet, and score. */
    public static SimilarRecord ofSnippet(String id, String snippet, Double score) {
        return new SimilarRecord(
                id, snippet, score, null, null, null, null, null, null, null, null, null);
    }
}
