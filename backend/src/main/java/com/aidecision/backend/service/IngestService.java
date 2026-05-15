package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.IngestRequest;
import com.aidecision.backend.dto.IngestResponse;
import com.aidecision.backend.entity.RiskEmbedding;
import com.aidecision.backend.entity.RiskFeature;
import com.aidecision.backend.entity.RiskIngestRecord;
import com.aidecision.backend.repository.RiskEmbeddingRepository;
import com.aidecision.backend.repository.RiskFeatureRepository;
import com.aidecision.backend.repository.RiskIngestRecordRepository;
import com.aidecision.backend.support.MetadataUserRefs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final String EMBEDDING_TYPE_RECORD = "feature";

    private final RiskIngestRecordRepository ingestRepo;
    private final RiskFeatureRepository featureRepo;
    private final RiskEmbeddingRepository embeddingRepo;
    private final AzureOpenAiEmbeddingService embeddingClient;
    private final AzureOpenAiProperties openAiProperties;
    private final AzureSearchIngestService searchIngestService;
    private final AzureSearchProperties searchProperties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final ActivityLogService activityLogService;

    public IngestService(
            RiskIngestRecordRepository ingestRepo,
            RiskFeatureRepository featureRepo,
            RiskEmbeddingRepository embeddingRepo,
            AzureOpenAiEmbeddingService embeddingClient,
            AzureOpenAiProperties openAiProperties,
            AzureSearchIngestService searchIngestService,
            AzureSearchProperties searchProperties,
            ObjectMapper mapper,
            TransactionTemplate transactionTemplate,
            ActivityLogService activityLogService) {
        this.ingestRepo = ingestRepo;
        this.featureRepo = featureRepo;
        this.embeddingRepo = embeddingRepo;
        this.embeddingClient = embeddingClient;
        this.openAiProperties = openAiProperties;
        this.searchIngestService = searchIngestService;
        this.searchProperties = searchProperties;
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
        this.activityLogService = activityLogService;
    }

    public IngestResponse ingest(IngestRequest req) {
        String recordId = UUID.randomUUID().toString();
        String mergedMetadata = mergeMetadata(req.metadata(), req.reviewOutcome());

        String embedText = buildRecordTextForEmbedding(req.text(), mergedMetadata, req.reviewOutcome(), recordId);

        final AzureOpenAiEmbeddingService.EmbeddingVector embeddingVector = resolveEmbedding(embedText);

        IngestResponse res = transactionTemplate.execute(status -> {
            RiskFeature feature = new RiskFeature();
            feature.setRequestId(recordId);
            feature.setSource("ingest");
            feature.setFeaturesJson(mergedMetadata);
            applyDenormalizedFromJson(feature, mergedMetadata);
            featureRepo.save(feature);

            RiskIngestRecord entity = new RiskIngestRecord();
            entity.setRecordUuid(recordId);
            entity.setReviewOutcome(req.reviewOutcome());
            entity.setText(req.text() != null && !req.text().isBlank() ? req.text().trim() : null);
            entity.setMetadata(mergedMetadata);
            RiskIngestRecord saved = ingestRepo.save(entity);

            String extraMsg = "";
            if (embeddingVector != null) {
                try {
                    String json = mapper.writeValueAsString(embeddingVector.values());
                    RiskEmbedding emb = new RiskEmbedding();
                    emb.setRequestId(recordId);
                    emb.setEmbeddingType(EMBEDDING_TYPE_RECORD);
                    emb.setEmbeddingJson(json);
                    emb.setDimensions(embeddingVector.dimensions());
                    emb.setModelName(truncate(embeddingVector.modelName(), 200));
                    emb.setModelVersion(openAiProperties.getApiVersion());
                    embeddingRepo.save(emb);
                    extraMsg = "; embedding " + embeddingVector.dimensions() + "-dim";
                } catch (Exception e) {
                    status.setRollbackOnly();
                    throw new IllegalStateException("Failed to persist embedding JSON", e);
                }
            }

            return new IngestResponse(
                    true,
                    saved.getId(),
                    saved.getRecordUuid(),
                    "Saved to Azure SQL (" + req.reviewOutcome() + ")" + extraMsg
            );
        });

        if (res == null) {
            throw new IllegalStateException("Ingest transaction returned no response");
        }

        IngestResponse out = res;
        if (embeddingVector != null) {
            if (searchProperties.isSkip()) {
                log.info("Skipping Azure AI Search (AZURE_SEARCH_SKIP=true)");
            } else if (!searchProperties.searchConfigured()) {
                throw new IllegalStateException(
                        "Azure AI Search is required after embedding. Set AZURE_SEARCH_ENDPOINT, "
                                + "AZURE_SEARCH_ADMIN_KEY (e.g. Key Vault secret ai-search), "
                                + "AZURE_SEARCH_INDEX_NAME (default risk-records), "
                                + "or set AZURE_SEARCH_SKIP=true to skip indexing.");
            } else {
                searchIngestService.uploadIngestDocument(recordId, req, mergedMetadata, embedText, embeddingVector);
                out = new IngestResponse(
                        res.ok(),
                        res.recordIndex(),
                        res.recordId(),
                        res.message() + "; Azure AI Search indexed");
            }
        }

        autologIngest(mergedMetadata, req.reviewOutcome(), out.recordId());
        return out;
    }

    private void autologIngest(String mergedMetadata, String reviewOutcome, String recordId) {
        MetadataUserRefs.UserTxn refs = MetadataUserRefs.parse(mergedMetadata, mapper);
        String txn = refs.transactionId() != null && !refs.transactionId().isBlank()
                ? refs.transactionId()
                : "ingest:" + recordId;
        activityLogService.tryAppendFromApi(
                refs.userId(),
                txn,
                ingestOutcomeToBiz(reviewOutcome),
                "add");
    }

    private static String ingestOutcomeToBiz(String reviewOutcome) {
        if (reviewOutcome == null) {
            return "pass";
        }
        return switch (reviewOutcome.toLowerCase(Locale.ROOT)) {
            case "passed" -> "pass";
            case "rejected" -> "reject";
            case "frozen" -> "freeze";
            default -> "pass";
        };
    }

    private AzureOpenAiEmbeddingService.EmbeddingVector resolveEmbedding(String embedText) {
        if (openAiProperties.isSkipEmbedding()) {
            log.info("Skipping Azure embedding (AZURE_OPENAI_SKIP_EMBEDDING=true)");
            return null;
        }
        if (!openAiProperties.embeddingConfigured()) {
            throw new IllegalStateException(
                    "Azure OpenAI embedding is required for ingest. Set AZURE_OPENAI_ENDPOINT, "
                            + "AZURE_OPENAI_API_KEY (e.g. Key Vault secret azure-openai-api-key), and "
                            + "AZURE_OPENAI_EMBEDDING_DEPLOYMENT to your embeddings deployment name, "
                            + "or set AZURE_OPENAI_SKIP_EMBEDDING=true for local dev without vectors.");
        }
        return embeddingClient.embed(embedText);
    }

    /** Single text blob sent to the embedding model: structured enough for retrieval. */
    private String buildRecordTextForEmbedding(
            String text,
            String mergedMetadataJson,
            String reviewOutcome,
            String recordId) {
        String notes = text != null && !text.isBlank() ? text.trim() : "";
        return "record_id=" + recordId + "\n"
                + "review_outcome=" + reviewOutcome + "\n"
                + "case_notes=\n" + notes + "\n"
                + "metadata_json=\n" + mergedMetadataJson;
    }

    private String mergeMetadata(String raw, String reviewOutcome) {
        ObjectNode node;
        try {
            if (raw != null && !raw.isBlank()) {
                var parsed = mapper.readTree(raw.trim());
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
        node.put("reviewOutcome", reviewOutcome);
        node.put("reviewOutcomeAt", Instant.now().toString());
        return node.toString();
    }

    private void applyDenormalizedFromJson(RiskFeature f, String mergedJson) {
        try {
            JsonNode n = mapper.readTree(mergedJson);
            if (!n.isObject()) return;
            if (n.hasNonNull("scenario")) f.setScenario(textOrNull(n.get("scenario")));
            if (n.hasNonNull("transaction_id")) f.setTransactionId(textOrNull(n.get("transaction_id")));
            if (n.hasNonNull("user_id")) f.setUserId(textOrNull(n.get("user_id")));
            if (n.hasNonNull("device_id")) f.setDeviceId(textOrNull(n.get("device_id")));
            if (n.hasNonNull("country_code")) f.setCountryCode(textOrNull(n.get("country_code")));
            f.setWithdrawAmount(decimalField(n, "withdraw_amount"));
            f.setDepositAmount(decimalField(n, "deposit_amount"));
            f.setTotalAmount(decimalField(n, "total_amount"));
        } catch (Exception ignored) {
            // keep denormalized columns null
        }
    }

    private static String textOrNull(JsonNode n) {
        String s = n.asText("");
        return s.isBlank() ? null : s;
    }

    private static BigDecimal decimalField(JsonNode parent, String key) {
        if (!parent.has(key) || parent.get(key).isNull()) return null;
        JsonNode v = parent.get(key);
        try {
            if (v.isNumber()) return v.decimalValue().setScale(2, RoundingMode.HALF_UP);
            String s = v.asText("").trim();
            if (s.isEmpty()) return null;
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
