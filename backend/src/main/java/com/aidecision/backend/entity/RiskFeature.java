package com.aidecision.backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "risk_features")
public class RiskFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, columnDefinition = "CHAR(36)")
    private String requestId;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "scenario", length = 200)
    private String scenario;

    @Column(name = "transaction_id", length = 200)
    private String transactionId;

    @Column(name = "user_id", length = 200)
    private String userId;

    @Column(name = "device_id", length = 200)
    private String deviceId;

    @Column(name = "country_code", length = 10)
    private String countryCode;

    @Column(name = "withdraw_amount", precision = 18, scale = 2)
    private BigDecimal withdrawAmount;

    @Column(name = "deposit_amount", precision = 18, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "features_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String featuresJson;

    @Column(name = "created_at", nullable = false, updatable = false,
            insertable = false, columnDefinition = "DATETIME2")
    private Instant createdAt;

    public Long getId() { return id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public BigDecimal getWithdrawAmount() { return withdrawAmount; }
    public void setWithdrawAmount(BigDecimal withdrawAmount) { this.withdrawAmount = withdrawAmount; }

    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getFeaturesJson() { return featuresJson; }
    public void setFeaturesJson(String featuresJson) { this.featuresJson = featuresJson; }

    public Instant getCreatedAt() { return createdAt; }
}
