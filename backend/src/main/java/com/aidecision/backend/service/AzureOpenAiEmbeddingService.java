package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AzureOpenAiEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiEmbeddingService.class);

    /** Azure embedding models enforce token limits; stay under a safe text length. */
    private static final int MAX_INPUT_CHARS = 120_000;

    private final AzureOpenAiProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AzureOpenAiEmbeddingService(AzureOpenAiProperties props, ObjectMapper mapper, RestClient http) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
    }

    /**
     * Calls Azure OpenAI embeddings for the given single input string.
     * @return embedding vector and dimensions
     */
    public EmbeddingVector embed(String input) {
        if (!props.embeddingConfigured()) {
            throw new IllegalStateException(
                    "Azure OpenAI is not configured. Set AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, "
                            + "AZURE_OPENAI_EMBEDDING_DEPLOYMENT (or app.azure-openai.*).");
        }
        String text = input == null ? "" : input;
        if (text.length() > MAX_INPUT_CHARS) {
            log.warn("Truncating embedding input from {} to {} chars", text.length(), MAX_INPUT_CHARS);
            text = text.substring(0, MAX_INPUT_CHARS);
        }

        String base = props.getEndpoint().replaceAll("/+$", "");
        String deploy = props.getEmbeddingDeployment();
        String version = props.getApiVersion();
        URI uri = URI.create(base + "/openai/deployments/" + deploy + "/embeddings?api-version=" + version);

        String body;
        try {
            body = mapper.writeValueAsString(Map.of("input", text));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize embedding request", e);
        }

        String raw;
        try {
            raw = http.post()
                    .uri(uri)
                    .header("api-key", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                String err = "";
                                try {
                                    err = new String(res.getBody().readAllBytes());
                                } catch (Exception ignored) { }
                                throw new IllegalStateException(
                                        "Azure OpenAI embeddings failed: HTTP " + res.getStatusCode() + " " + err);
                            })
                    .body(String.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Azure OpenAI embeddings request failed: " + e.getMessage(), e);
        }

        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode data0 = root.path("data").path(0);
            JsonNode emb = data0.path("embedding");
            if (!emb.isArray() || emb.isEmpty()) {
                throw new IllegalStateException("Azure OpenAI response missing data[0].embedding");
            }
            List<Double> vec = new ArrayList<>(emb.size());
            for (JsonNode n : emb) {
                vec.add(n.asDouble());
            }
            String model = root.path("model").asText(props.getEmbeddingDeployment());
            return new EmbeddingVector(vec, model);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse embedding response", e);
        }
    }

    public record EmbeddingVector(List<Double> values, String modelName) {
        public int dimensions() { return values.size(); }
    }
}
