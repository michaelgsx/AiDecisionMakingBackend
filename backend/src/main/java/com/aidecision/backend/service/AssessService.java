package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.aidecision.backend.dto.SimilarRecord;
import com.aidecision.backend.support.MetadataUserRefs;
import com.aidecision.backend.support.TextFeatureSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssessService {

    private static final Logger log = LoggerFactory.getLogger(AssessService.class);

    private static final int SIMILAR_TOP = 8;
    /** Max chars per large field inside {@link SimilarRecord#readableText()} (full JSON still in raw fields). */
    private static final int READABLE_SECTION_CAP = 12_000;

    private final AzureOpenAiEmbeddingService embeddingClient;
    private final AzureOpenAiChatService chatService;
    private final AzureOpenAiProperties openAiProperties;
    private final AzureSearchQueryService searchQueryService;
    private final AzureSearchProperties searchProperties;
    private final ObjectMapper mapper;
    private final ActivityLogService activityLogService;

    public AssessService(
            AzureOpenAiEmbeddingService embeddingClient,
            AzureOpenAiChatService chatService,
            AzureOpenAiProperties openAiProperties,
            AzureSearchQueryService searchQueryService,
            AzureSearchProperties searchProperties,
            ObjectMapper mapper,
            ActivityLogService activityLogService) {
        this.embeddingClient = embeddingClient;
        this.chatService = chatService;
        this.openAiProperties = openAiProperties;
        this.searchQueryService = searchQueryService;
        this.searchProperties = searchProperties;
        this.mapper = mapper;
        this.activityLogService = activityLogService;
    }

    public AssessResponse assess(AssessRequest req) {
        if (!searchProperties.searchConfigured() || searchProperties.isSkip()) {
            return new AssessResponse(
                    "low",
                    "Azure AI Search is not configured (set AZURE_SEARCH_* or turn off AZURE_SEARCH_SKIP). "
                            + "Similar cases require the indexed ingest pipeline.",
                    List.of(),
                    null,
                    null
            );
        }

        String narrative = req.text() != null ? req.text().trim() : "";
        String mergedMeta = mergeAssessMetadata(req.metadata());
        boolean hasNarrative = !narrative.isBlank();
        boolean hasMeta = mergedMeta != null && !mergedMeta.isBlank() && !"{}".equals(mergedMeta.trim());

        if (!hasNarrative && !hasMeta) {
            return new AssessResponse(
                    "low",
                    "Provide case notes or at least one risk feature to search for similar records.",
                    List.of(),
                    null,
                    null
            );
        }

        String queryId = "assess-" + UUID.randomUUID();
        String embedText = buildAssessEmbedText(narrative, mergedMeta, queryId);
        String lexical = buildLexicalQuery(narrative, mergedMeta);

        List<Double> caseVector = null;
        List<Double> textVector = null;
        if (openAiProperties.embeddingConfigured() && !openAiProperties.isSkipEmbedding()) {
            try {
                caseVector = embeddingClient.embed(embedText).values();
                String textBlob = TextFeatureSupport.buildTextBlob(narrative, mergedMeta, mapper);
                if (textBlob != null && !textBlob.isBlank()) {
                    textVector = embeddingClient.embed(textBlob).values();
                }
            } catch (Exception e) {
                log.warn("Embedding failed; falling back to lexical-only search: {}", e.getMessage());
            }
        } else if (lexical.isBlank()) {
            return new AssessResponse(
                    "low",
                    "Azure OpenAI embedding is not configured and no searchable text was provided. "
                            + "Add text/metadata or configure AZURE_OPENAI_* for vector similarity.",
                    List.of(),
                    null,
                    null
            );
        }

        List<AzureSearchQueryService.SimilarHit> hits;
        try {
            hits = searchQueryService.searchSimilar(lexical, caseVector, textVector, SIMILAR_TOP);
        } catch (Exception e) {
            log.error("Azure AI Search assess failed", e);
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : "Search failed", e);
        }

        List<SimilarRecord> similar = new ArrayList<>();
        double maxScore = 0;
        for (AzureSearchQueryService.SimilarHit h : hits) {
            similar.add(toSimilarRecord(h));
            maxScore = Math.max(maxScore, h.score());
        }

        String risk = similar.isEmpty() ? "low" : (maxScore >= 0.75 ? "high" : "low");
        String mode = describeSearchMode(caseVector, textVector);
        String reason = similar.isEmpty()
                ? "No indexed cases matched this query yet. Ingest a few records into Azure AI Search first."
                : String.format(
                        "Found %d similar case(s) via Azure AI Search (%s). Best @search.score ≈ %.4f.",
                        similar.size(),
                        mode,
                        maxScore
                );

        String aiLabel = null;
        String aiReason = null;
        if (openAiProperties.chatConfigured()) {
            try {
                AzureOpenAiChatService.LabelDecision d =
                        chatService.classifyWithSimilar(narrative, mergedMeta, similar);
                aiLabel = d.label();
                aiReason = d.reason();
                risk = riskFromOutcomeLabel(aiLabel);
            } catch (Exception e) {
                log.warn("Assess chat step failed; returning search summary only: {}", e.getMessage());
            }
        }

        autologAssess(mergedMeta, queryId);
        return new AssessResponse(risk, reason, similar, aiLabel, aiReason);
    }

    private void autologAssess(String mergedMeta, String queryId) {
        MetadataUserRefs.UserTxn refs = MetadataUserRefs.parse(mergedMeta, mapper);
        String txn = refs.transactionId() != null && !refs.transactionId().isBlank()
                ? refs.transactionId()
                : queryId;
        // "pass" = assessment API invoked (not a business approval decision).
        activityLogService.tryAppendFromApi(refs.userId(), txn, "pass", "add");
    }

    private static SimilarRecord toSimilarRecord(AzureSearchQueryService.SimilarHit h) {
        String displayId = h.id() != null && !h.id().isBlank() ? h.id() : null;
        String readable = formatReadableSimilar(h);
        return new SimilarRecord(
                displayId,
                h.snippet(),
                h.score(),
                blankToNull(h.recordId()),
                blankToNull(h.reviewOutcome()),
                blankToNull(h.caseNotes()),
                blankToNull(h.metadataJson()),
                blankToNull(h.content()),
                blankToNull(h.userId()),
                blankToNull(h.scenario()),
                blankToNull(h.transactionId()),
                readable);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private static String formatReadableSimilar(AzureSearchQueryService.SimilarHit h) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Similar record ===\n");
        sb.append("record_id: ").append(h.id() != null && !h.id().isBlank() ? h.id() : "(unknown)").append('\n');
        sb.append("review_outcome: ").append(h.reviewOutcome() == null || h.reviewOutcome().isBlank()
                ? "(unknown)"
                : h.reviewOutcome()).append('\n');
        sb.append("azure_search_score: ").append(String.format(Locale.ROOT, "%.6f", h.score())).append('\n');
        if (h.userId() != null && !h.userId().isBlank()) {
            sb.append("user_id: ").append(h.userId()).append('\n');
        }
        if (h.scenario() != null && !h.scenario().isBlank()) {
            sb.append("scenario: ").append(h.scenario()).append('\n');
        }
        if (h.transactionId() != null && !h.transactionId().isBlank()) {
            sb.append("transaction_id: ").append(h.transactionId()).append('\n');
        }
        sb.append('\n');
        appendReadableSection(sb, "Case notes", h.caseNotes());
        appendReadableSection(sb, "Risk features (metadata JSON)", h.metadataJson());
        appendReadableSection(sb, "Indexed content (full text used for embedding / hybrid search)", h.content());
        sb.append("--- One-line preview ---\n").append(h.snippet() != null ? h.snippet().trim() : "").append('\n');
        return sb.toString().trim();
    }

    private static void appendReadableSection(StringBuilder sb, String title, String body) {
        sb.append("-- ").append(title).append(" --\n");
        if (body == null || body.isBlank()) {
            sb.append("(none)\n\n");
            return;
        }
        String t = body.length() > READABLE_SECTION_CAP
                ? body.substring(0, READABLE_SECTION_CAP) + "\n… (truncated in readableText; see JSON fields for full text)"
                : body;
        sb.append(t).append("\n\n");
    }

    private String describeSearchMode(List<Double> caseVector, List<Double> textVector) {
        boolean hasCase = caseVector != null && !caseVector.isEmpty();
        boolean hasText = textVector != null && !textVector.isEmpty();
        if (!hasCase && !hasText) {
            return "lexical";
        }
        if (hasCase && hasText) {
            return String.format(
                    Locale.ROOT,
                    "hybrid lexical + dual-vector (case %.2f, text %.2f)",
                    searchProperties.normalizedCaseWeight(true, true),
                    searchProperties.normalizedTextWeight(true, true));
        }
        return hasCase ? "hybrid lexical + case vector" : "hybrid lexical + text vector";
    }

    private static String riskFromOutcomeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "low";
        }
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "rejected" -> "high";
            case "frozen" -> "medium";
            default -> "low";
        };
    }

    /** Aligns with ingest embedding text shape (review_outcome=unspecified for query). */
    private String buildAssessEmbedText(String narrative, String mergedMetadataJson, String queryId) {
        return "record_id=" + queryId + "\n"
                + "review_outcome=unspecified\n"
                + "case_notes=\n" + narrative + "\n"
                + "metadata_json=\n" + mergedMetadataJson;
    }

    private String mergeAssessMetadata(String raw) {
        ObjectNode node;
        try {
            if (raw != null && !raw.isBlank()) {
                JsonNode parsed = mapper.readTree(raw.trim());
                if (parsed.isObject()) {
                    node = (ObjectNode) parsed;
                } else {
                    node = mapper.createObjectNode();
                    node.put("_previousMetadata", raw.trim());
                }
            } else {
                node = mapper.createObjectNode();
            }
        } catch (Exception e) {
            node = mapper.createObjectNode();
            if (raw != null && !raw.isBlank()) {
                node.put("_previousMetadata", raw.trim());
            }
        }
        node.put("assessQueryAt", Instant.now().toString());
        return node.toString();
    }

    private String buildLexicalQuery(String narrative, String mergedMeta) {
        StringBuilder sb = new StringBuilder();
        if (narrative != null && !narrative.isBlank()) {
            sb.append(narrative.trim());
        }
        if (mergedMeta != null && !mergedMeta.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(mergedMeta);
        }
        String s = sb.toString().trim();
        if (s.length() > 32_000) {
            s = s.substring(0, 32_000);
        }
        return s;
    }
}
