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

    /** Index vector field for full-case embedding (notes + metadata blob). */
    private String vectorFieldCase = "contentVector";

    /** Index vector field for NL-only embedding (email + conversation). */
    private String vectorFieldText = "textVector";

    /** Relative weight in multi-vector hybrid query (see AZURE_SEARCH_VECTOR_WEIGHT_*). */
    private double vectorWeightCase = 0.6;

    private double vectorWeightText = 0.4;

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

    public String getVectorFieldCase() { return vectorFieldCase; }
    public void setVectorFieldCase(String vectorFieldCase) {
        this.vectorFieldCase = vectorFieldCase == null || vectorFieldCase.isBlank()
                ? "contentVector" : vectorFieldCase.trim();
    }

    public String getVectorFieldText() { return vectorFieldText; }
    public void setVectorFieldText(String vectorFieldText) {
        this.vectorFieldText = vectorFieldText == null || vectorFieldText.isBlank()
                ? "textVector" : vectorFieldText.trim();
    }

    public double getVectorWeightCase() { return vectorWeightCase; }
    public void setVectorWeightCase(double vectorWeightCase) { this.vectorWeightCase = vectorWeightCase; }

    public double getVectorWeightText() { return vectorWeightText; }
    public void setVectorWeightText(double vectorWeightText) { this.vectorWeightText = vectorWeightText; }

    /** Weights for active vector queries, normalized to sum to 1. */
    public double normalizedCaseWeight(boolean casePresent, boolean textPresent) {
        if (!casePresent && !textPresent) {
            return 0;
        }
        if (casePresent && !textPresent) {
            return 1.0;
        }
        if (!casePresent && textPresent) {
            return 0;
        }
        double sum = Math.max(1e-9, vectorWeightCase + vectorWeightText);
        return vectorWeightCase / sum;
    }

    public double normalizedTextWeight(boolean casePresent, boolean textPresent) {
        if (!casePresent && !textPresent) {
            return 0;
        }
        if (!casePresent && textPresent) {
            return 1.0;
        }
        if (casePresent && !textPresent) {
            return 0;
        }
        double sum = Math.max(1e-9, vectorWeightCase + vectorWeightText);
        return vectorWeightText / sum;
    }
}
