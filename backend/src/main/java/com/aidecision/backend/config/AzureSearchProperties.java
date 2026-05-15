package com.aidecision.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.azure-search")
public class AzureSearchProperties {

    /** Service URL, e.g. https://xyz.search.windows.net (no trailing slash) */
    private String endpoint = "";

    /** Admin API key (use Key Vault reference on App Service). */
    private String adminKey = "";

    /** Index name; must match created index (default risk-records). */
    private String indexName = "risk-records";

    private String apiVersion = "2024-07-01";

    /** When true, do not push documents (local dev). */
    private boolean skip = false;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    public String getAdminKey() { return adminKey; }
    public void setAdminKey(String adminKey) {
        this.adminKey = adminKey == null ? "" : adminKey;
    }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) {
        this.indexName = indexName == null ? "" : indexName.trim();
    }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion == null ? "2024-07-01" : apiVersion.trim();
    }

    public boolean isSkip() { return skip; }
    public void setSkip(boolean skip) { this.skip = skip; }

    public boolean searchConfigured() {
        return !endpoint.isBlank() && !adminKey.isBlank() && !indexName.isBlank();
    }

    /** Upload to AI Search when we have an embedding and config is present. */
    public boolean shouldUploadDocuments() {
        return !skip && searchConfigured();
    }
}
