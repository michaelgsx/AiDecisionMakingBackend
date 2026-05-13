-- V2: Create risk_features table — one row per request (ingest or risk-similarity).
-- Commonly queried core fields are denormalized; full feature set stored as JSON.

IF OBJECT_ID(N'dbo.risk_features', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_features (
    id               BIGINT        IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    request_id       CHAR(36)      NOT NULL,
    source           VARCHAR(20)   NOT NULL,
    scenario         NVARCHAR(200) NULL,
    transaction_id   NVARCHAR(200) NULL,
    user_id          NVARCHAR(200) NULL,
    device_id        NVARCHAR(200) NULL,
    country_code     NVARCHAR(10)  NULL,
    withdraw_amount  DECIMAL(18, 2) NULL,
    deposit_amount   DECIMAL(18, 2) NULL,
    total_amount     DECIMAL(18, 2) NULL,
    features_json    NVARCHAR(MAX) NOT NULL,
    created_at       DATETIME2     NOT NULL
      CONSTRAINT DF_risk_features_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UK_risk_features_request_id UNIQUE (request_id),
    CONSTRAINT CK_risk_features_source CHECK (source IN ('ingest', 'risk-similarity'))
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_features_created_at'    AND object_id = OBJECT_ID(N'dbo.risk_features'))
  CREATE INDEX IX_risk_features_created_at    ON dbo.risk_features (created_at);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_features_source'         AND object_id = OBJECT_ID(N'dbo.risk_features'))
  CREATE INDEX IX_risk_features_source         ON dbo.risk_features (source);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_features_user_id'        AND object_id = OBJECT_ID(N'dbo.risk_features'))
  CREATE INDEX IX_risk_features_user_id        ON dbo.risk_features (user_id) WHERE user_id IS NOT NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_features_transaction_id' AND object_id = OBJECT_ID(N'dbo.risk_features'))
  CREATE INDEX IX_risk_features_transaction_id ON dbo.risk_features (transaction_id) WHERE transaction_id IS NOT NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_features_scenario'       AND object_id = OBJECT_ID(N'dbo.risk_features'))
  CREATE INDEX IX_risk_features_scenario       ON dbo.risk_features (scenario) WHERE scenario IS NOT NULL;
GO
