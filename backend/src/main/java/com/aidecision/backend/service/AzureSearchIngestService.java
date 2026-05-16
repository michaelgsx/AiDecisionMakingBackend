package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.IngestRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pushes ingest documents to Azure AI Search for hybrid (lexical + dual-vector) retrieval.
 */
@Service
public class AzureSearchIngestService {

    private static final Logger log = LoggerFactory.getLogger(AzureSearchIngestService.class);

    private final AzureSearchProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AzureSearchIngestService(AzureSearchProperties props, ObjectMapper mapper, RestClient http) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
    }

    /**
     * Upload with case (full-record) embedding only; textVector omitted.
     */
    public void uploadIngestDocument(
            String recordId,
            IngestRequest req,
            String mergedMetadataJson,
            String mergedText,
            AzureOpenAiEmbeddingService.EmbeddingVector caseVector) {
        uploadIngestDocument(recordId, req, mergedMetadataJson, mergedText, caseVector, null);
    }

    /**
     * @param caseVector embedding of full record blob → {@code contentVector}
     * @param textVector embedding of NL fields only → {@code textVector}; may be null
     */
    public void uploadIngestDocument(
            String recordId,
            IngestRequest req,
            String mergedMetadataJson,
            String mergedText,
            AzureOpenAiEmbeddingService.EmbeddingVector caseVector,
            AzureOpenAiEmbeddingService.EmbeddingVector textVector) {

        if (!props.shouldUploadDocuments()) {
            return;
        }
        if (caseVector == null) {
            throw new IllegalArgumentException("caseVector is required for Azure AI Search upload");
        }

        String caseNotes = req.text() != null && !req.text().isBlank() ? req.text().trim() : "";

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@search.action", "upload");
        doc.put("id", recordId);
        doc.put("recordId", recordId);
        doc.put("reviewOutcome", nullToEmpty(req.reviewOutcome()));
        doc.put("caseNotes", caseNotes);
        doc.put("metadataJson", mergedMetadataJson != null ? mergedMetadataJson : "{}");
        doc.put("content", mergedText != null ? mergedText : "");
        doc.put("embeddingModel", truncate(caseVector.modelName(), 200));
        doc.put("embeddingDimensions", caseVector.dimensions());
        doc.put(props.getVectorFieldCase(), toFloats(caseVector.values()));

        if (textVector != null && !textVector.values().isEmpty()) {
            doc.put(props.getVectorFieldText(), toFloats(textVector.values()));
        }

        String userId = "";
        String scenario = "";
        String transactionId = "";
        try {
            JsonNode n = mapper.readTree(mergedMetadataJson != null ? mergedMetadataJson : "{}");
            if (n.isObject()) {
                userId = textField(n, "user_id");
                scenario = textField(n, "scenario");
                transactionId = textField(n, "transaction_id");
            }
        } catch (Exception ignored) { }

        doc.put("userId", userId);
        doc.put("scenario", scenario);
        doc.put("transactionId", transactionId);

        postIndexBatch(doc);
    }

    private void postIndexBatch(Map<String, Object> doc) {
        Map<String, Object> envelope = Map.of("value", List.of(doc));
        String body;
        try {
            body = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Azure Search document", e);
        }

        String base = props.getEndpoint().replaceAll("/+$", "");
        URI uri = URI.create(base + "/indexes/" + props.getIndexName()
                + "/docs/index?api-version=" + props.getApiVersion());

        try {
            String status = http.post()
                    .uri(uri)
                    .header("api-key", props.getAdminKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(
                            s -> s.is4xxClientError() || s.is5xxServerError(),
                            (request, response) -> {
                                String err = "";
                                try {
                                    err = new String(response.getBody().readAllBytes());
                                } catch (Exception ignored) { }
                                throw new IllegalStateException(
                                        "Azure AI Search index failed: HTTP " + response.getStatusCode() + " " + err);
                            })
                    .body(String.class);

            if (log.isDebugEnabled()) {
                log.debug("Azure AI Search index response: {}", status);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Azure AI Search request failed: " + e.getMessage(), e);
        }
    }

    private static List<Float> toFloats(List<Double> values) {
        return values.stream().map(Double::floatValue).collect(Collectors.toCollection(ArrayList::new));
    }

    private static String textField(JsonNode parent, String key) {
        if (!parent.has(key) || parent.get(key).isNull()) return "";
        String s = parent.get(key).asText("");
        return s == null ? "" : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
