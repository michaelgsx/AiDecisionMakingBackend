package com.aidecision.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "activity_log")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 200)
    private String userId;

    @Column(name = "transaction_id", nullable = false, length = 200)
    private String transactionId;

    @Column(name = "biz_action", nullable = false, length = 10)
    private String bizAction;

    @Column(name = "record_action", nullable = false, length = 10)
    private String recordAction;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "hash_code", nullable = false, length = 64, unique = true)
    private String hashCode;

    @Column(name = "created_at", nullable = false, updatable = false,
            insertable = false, columnDefinition = "DATETIME2")
    private Instant createdAt;

    public Long getId() { return id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getBizAction() { return bizAction; }
    public void setBizAction(String bizAction) { this.bizAction = bizAction; }

    public String getRecordAction() { return recordAction; }
    public void setRecordAction(String recordAction) { this.recordAction = recordAction; }

    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }

    public String getHashCode() { return hashCode; }
    public void setHashCode(String hashCode) { this.hashCode = hashCode; }

    public Instant getCreatedAt() { return createdAt; }
}
