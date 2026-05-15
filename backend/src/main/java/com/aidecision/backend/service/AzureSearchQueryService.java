package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Hybrid / vector search against Azure AI Search (same index as ingest).
 */
@Service
public class AzureSearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(AzureSearchQueryService.class);

    private final AzureSearchProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AzureSearchQueryService(AzureSearchProperties props, ObjectMapper mapper, RestClient http) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
    }

    public record SimilarHit(String id, String snippet, double score) {}

    /**
     * @param lexicalQuery BM25 text (case notes + metadata excerpt); may be blank for vector-only
     * @param queryVector  embedding from Azure OpenAI; may be null for lexical-only
     * @param top          max documents to return
     */
    public List<SimilarHit> searchSimilar(String lexicalQuery, List<Double> queryVector, int top) {
        if (!props.searchConfigured() || props.isSkip()) {
            throw new IllegalStateException("Azure AI Search is not configured for queries.");
        }

        String base = props.getEndpoint().replaceAll("/+$", "");
        URI uri = URI.create(base + "/indexes/" + props.getIndexName()
                + "/docs/search?api-version=" + props.getApiVersion());

        ObjectNode root = mapper.createObjectNode();
        root.put("top", top);
        root.put("count", true);
        root.set("select", mapper.createArrayNode()
                .add("id")
                .add("recordId")
                .add("reviewOutcome")
                .add("caseNotes")
                .add("metadataJson")
                .add("content"));

        String lex = lexicalQuery != null ? lexicalQuery.trim() : "";
        if (!lex.isEmpty()) {
            root.put("search", lex);
        }

        if (queryVector != null && !queryVector.isEmpty()) {
            ArrayNode vec = mapper.createArrayNode();
            for (double d : queryVector) {
                vec.add(d);
            }
            ObjectNode vq = mapper.createObjectNode();
            vq.put("kind", "vector");
            vq.set("vector", vec);
            vq.put("fields", "contentVector");
            vq.put("k", Math.max(top * 4, 20));
            ArrayNode vqs = mapper.createArrayNode();
            vqs.add(vq);
            root.set("vectorQueries", vqs);
        }

        String body;
        try {
            body = mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build search request", e);
        }

        if (log.isDebugEnabled()) {
            log.debug("Azure AI Search request: {}", body.length() > 2000 ? body.substring(0, 2000) + "…" : body);
        }

        String raw;
        try {
            raw = http.post()
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
                                        "Azure AI Search query failed: HTTP " + response.getStatusCode() + " " + err);
                            })
                    .body(String.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Azure AI Search query failed: " + e.getMessage(), e);
        }

        try {
            JsonNode tree = mapper.readTree(raw);
            JsonNode values = tree.path("value");
            List<SimilarHit> out = new ArrayList<>();
            if (!values.isArray()) {
                return out;
            }
            for (JsonNode doc : values) {
                String id = textOrEmpty(doc.path("recordId")).isBlank()
                        ? textOrEmpty(doc.path("id"))
                        : textOrEmpty(doc.path("recordId"));
                double score = scoreOf(doc);
                String snippet = buildSnippet(doc);
                out.add(new SimilarHit(id, snippet, score));
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse search response", e);
        }
    }

    private static double scoreOf(JsonNode doc) {
        if (doc.has("@search.rerankerScore")) {
            return doc.path("@search.rerankerScore").asDouble(0);
        }
        if (doc.has("@search.score")) {
            return doc.path("@search.score").asDouble(0);
        }
        return 0;
    }

    private static String textOrEmpty(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? "" : n.asText("");
    }

    private static String buildSnippet(JsonNode doc) {
        String outcome = textOrEmpty(doc.path("reviewOutcome"));
        String notes = textOrEmpty(doc.path("caseNotes"));
        String meta = textOrEmpty(doc.path("metadataJson"));
        StringBuilder sb = new StringBuilder();
        if (!outcome.isBlank()) {
            sb.append("[").append(outcome).append("] ");
        }
        if (!notes.isBlank()) {
            sb.append(truncate(notes, 400));
        } else if (!meta.isBlank()) {
            sb.append(truncate(meta, 400));
        } else {
            sb.append(truncate(textOrEmpty(doc.path("content")), 400));
        }
        return sb.toString().trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
