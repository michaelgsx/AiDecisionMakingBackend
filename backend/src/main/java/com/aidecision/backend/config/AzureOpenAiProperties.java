package com.aidecision.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.azure-openai")
public class AzureOpenAiProperties {

    /** Base URL, e.g. https://ai-reg-embedding.openai.azure.com (no trailing slash) */
    private String endpoint = "";

    private String apiKey = "";

    /** Deployment name from Azure OpenAI studio (not necessarily the model id). */
    private String embeddingDeployment = "";

    /** Chat deployment for /rag/assess label + rationale (e.g. gpt-4o). */
    private String chatDeployment = "";

    private String apiVersion = "2024-02-01";

    /** API version for chat completions; if blank, defaults to {@link #DEFAULT_CHAT_API_VERSION}. */
    private String chatApiVersion = "";

    /** When true, ingest skips Azure embedding (local dev). */
    private boolean skipEmbedding = false;

    /** When true, assess skips the chat label step (similar search still runs). */
    private boolean skipChat = false;

    public static final String DEFAULT_CHAT_API_VERSION = "2024-08-01-preview";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint.trim(); }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public String getEmbeddingDeployment() { return embeddingDeployment; }
    public void setEmbeddingDeployment(String embeddingDeployment) { this.embeddingDeployment = embeddingDeployment == null ? "" : embeddingDeployment.trim(); }

    public String getChatDeployment() { return chatDeployment; }
    public void setChatDeployment(String chatDeployment) { this.chatDeployment = chatDeployment == null ? "" : chatDeployment.trim(); }

    public String getChatApiVersion() { return chatApiVersion; }
    public void setChatApiVersion(String chatApiVersion) { this.chatApiVersion = chatApiVersion == null ? "" : chatApiVersion.trim(); }

    /** Non-blank chat API version, or {@link #DEFAULT_CHAT_API_VERSION}. */
    public String getEffectiveChatApiVersion() {
        return chatApiVersion.isBlank() ? DEFAULT_CHAT_API_VERSION : chatApiVersion;
    }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion == null ? "2024-02-01" : apiVersion.trim(); }

    public boolean isSkipEmbedding() { return skipEmbedding; }
    public void setSkipEmbedding(boolean skipEmbedding) { this.skipEmbedding = skipEmbedding; }

    public boolean isSkipChat() { return skipChat; }
    public void setSkipChat(boolean skipChat) { this.skipChat = skipChat; }

    public boolean embeddingConfigured() {
        return !endpoint.isBlank() && !apiKey.isBlank() && !embeddingDeployment.isBlank();
    }

    /** Chat step for assess: same endpoint/key as embeddings, separate deployment name. */
    public boolean chatConfigured() {
        return !skipChat && !endpoint.isBlank() && !apiKey.isBlank() && !chatDeployment.isBlank();
    }
}
