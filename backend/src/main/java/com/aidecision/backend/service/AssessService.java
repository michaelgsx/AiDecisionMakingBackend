package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.aidecision.backend.dto.SimilarRecord;
import com.aidecision.backend.support.MetadataUserRefs;
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

        List<Double> vector = null;
        if (openAiProperties.embeddingConfigured() && !openAiProperties.isSkipEmbedding()) {
            try {
                vector = embeddingClient.embed(embedText).values();
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
            hits = searchQueryService.searchSimilar(lexical, vector, SIMILAR_TOP);
        } catch (Exception e) {
            log.error("Azure AI Search assess failed", e);
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : "Search failed", e);
        }

        List<SimilarRecord> similar = new ArrayList<>();
        double maxScore = 0;
        for (AzureSearchQueryService.SimilarHit h : hits) {
            String id = h.id() != null && !h.id().isBlank() ? h.id() : null;
            similar.add(new SimilarRecord(id, h.snippet(), h.score()));
            maxScore = Math.max(maxScore, h.score());
        }

        String risk = similar.isEmpty() ? "low" : (maxScore >= 0.75 ? "high" : "low");
        String mode = vector != null ? "hybrid (lexical + vector)" : "lexical";
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
