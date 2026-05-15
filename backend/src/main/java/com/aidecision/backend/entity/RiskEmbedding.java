package com.aidecision.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "risk_embeddings")
public class RiskEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, columnDefinition = "CHAR(36)")
    private String requestId;

    @Column(name = "embedding_type", nullable = false, length = 30)
    private String embeddingType;

    @Column(name = "embedding_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String embeddingJson;

    @Column(name = "dimensions", nullable = false)
    private int dimensions;

    @Column(name = "model_name", nullable = false, length = 200)
    private String modelName;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "created_at", nullable = false, updatable = false,
            insertable = false, columnDefinition = "DATETIME2")
    private Instant createdAt;

    public Long getId() { return id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getEmbeddingType() { return embeddingType; }
    public void setEmbeddingType(String embeddingType) { this.embeddingType = embeddingType; }

    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }

    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Instant getCreatedAt() { return createdAt; }
}
