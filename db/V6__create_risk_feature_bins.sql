-- V6: Offline quantile bin calibrations + per-request binned vectors (part 2 of feature pipeline).
-- Populated by: python db/build_quantile_bins.py

IF OBJECT_ID(N'dbo.risk_feature_bin_calibrations', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_feature_bin_calibrations (
    calibration_id        CHAR(36)      NOT NULL PRIMARY KEY,
    n_bins                INT           NOT NULL
      CONSTRAINT DF_risk_bin_cal_n_bins DEFAULT (5),
    strategy              VARCHAR(32)   NOT NULL
      CONSTRAINT DF_risk_bin_cal_strategy DEFAULT ('quantile'),
    training_row_count    INT           NOT NULL,
    numeric_feature_count INT           NOT NULL,
    id_features_json      NVARCHAR(MAX) NULL,
    numeric_features_json NVARCHAR(MAX) NULL,
    created_at            DATETIME2     NOT NULL
      CONSTRAINT DF_risk_bin_cal_created_at DEFAULT SYSUTCDATETIME()
  );
END;
GO

IF OBJECT_ID(N'dbo.risk_feature_bin_edges', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_feature_bin_edges (
    calibration_id  CHAR(36)       NOT NULL,
    feature_name    NVARCHAR(200)  NOT NULL,
    edges_json      NVARCHAR(MAX)  NOT NULL,
    bin_counts_json NVARCHAR(MAX)  NOT NULL,
    sample_size     INT            NOT NULL,
    CONSTRAINT PK_risk_feature_bin_edges PRIMARY KEY (calibration_id, feature_name),
    CONSTRAINT FK_risk_bin_edges_calibration
      FOREIGN KEY (calibration_id) REFERENCES dbo.risk_feature_bin_calibrations (calibration_id)
  );
END;
GO

IF OBJECT_ID(N'dbo.risk_feature_binned', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_feature_binned (
    request_id      CHAR(36)      NOT NULL,
    calibration_id  CHAR(36)      NOT NULL,
    binned_json     NVARCHAR(MAX) NOT NULL,
    vector_json     NVARCHAR(MAX) NOT NULL,
    created_at      DATETIME2     NOT NULL
      CONSTRAINT DF_risk_feature_binned_created DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_risk_feature_binned PRIMARY KEY (request_id, calibration_id),
    CONSTRAINT FK_risk_feature_binned_request
      FOREIGN KEY (request_id) REFERENCES dbo.risk_features (request_id),
    CONSTRAINT FK_risk_feature_binned_calibration
      FOREIGN KEY (calibration_id) REFERENCES dbo.risk_feature_bin_calibrations (calibration_id)
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_feature_binned_calibration' AND object_id = OBJECT_ID(N'dbo.risk_feature_binned'))
  CREATE INDEX IX_risk_feature_binned_calibration ON dbo.risk_feature_binned (calibration_id);
GO

-- Allow separate email vs conversation embeddings (part 3).
IF EXISTS (
  SELECT 1 FROM sys.check_constraints
  WHERE name = N'CK_risk_embeddings_type' AND parent_object_id = OBJECT_ID(N'dbo.risk_embeddings')
)
BEGIN
  ALTER TABLE dbo.risk_embeddings DROP CONSTRAINT CK_risk_embeddings_type;
END;
GO

IF OBJECT_ID(N'dbo.risk_embeddings', N'U') IS NOT NULL
BEGIN
  ALTER TABLE dbo.risk_embeddings ADD CONSTRAINT CK_risk_embeddings_type
    CHECK (embedding_type IN ('feature', 'conversation', 'graph', 'email', 'text_combined'));
END;
GO
