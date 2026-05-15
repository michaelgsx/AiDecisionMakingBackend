package com.aidecision.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.azure-openai")
public class AzureOpenAiProperties {

    /** Base URL, e.g. https://ai-reg-embedding.openai.azure.com (no trailing slash) */
    private String endpoint = "";

    private String apiKey = "";

    /** Deployment name from Azure OpenAI studio (not necessarily the model id). */
    private String embeddingDeployment = "";

    private String apiVersion = "2024-02-01";

    /** When true, ingest skips Azure embedding (local dev). */
    private boolean skipEmbedding = false;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint.trim(); }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public String getEmbeddingDeployment() { return embeddingDeployment; }
    public void setEmbeddingDeployment(String embeddingDeployment) { this.embeddingDeployment = embeddingDeployment == null ? "" : embeddingDeployment.trim(); }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion == null ? "2024-02-01" : apiVersion.trim(); }

    public boolean isSkipEmbedding() { return skipEmbedding; }
    public void setSkipEmbedding(boolean skipEmbedding) { this.skipEmbedding = skipEmbedding; }

    public boolean embeddingConfigured() {
        return !endpoint.isBlank() && !apiKey.isBlank() && !embeddingDeployment.isBlank();
    }
}
