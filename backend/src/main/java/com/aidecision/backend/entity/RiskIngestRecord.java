package com.aidecision.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "risk_ingest_records")
public class RiskIngestRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_uuid", nullable = false, unique = true, columnDefinition = "CHAR(36)")
    private String recordUuid;

    @Column(name = "review_outcome", nullable = false, length = 20)
    private String reviewOutcome;

    @Column(name = "text", columnDefinition = "NVARCHAR(MAX)")
    private String text;

    @Column(name = "metadata", columnDefinition = "NVARCHAR(MAX)")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false,
            insertable = false, columnDefinition = "DATETIME2")
    private Instant createdAt;

    public Long getId() { return id; }

    public String getRecordUuid() { return recordUuid; }
    public void setRecordUuid(String recordUuid) { this.recordUuid = recordUuid; }

    public String getReviewOutcome() { return reviewOutcome; }
    public void setReviewOutcome(String reviewOutcome) { this.reviewOutcome = reviewOutcome; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
}
