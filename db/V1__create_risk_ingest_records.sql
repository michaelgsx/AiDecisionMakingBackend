-- V1: Create risk_ingest_records table for Azure SQL Database.
-- Run once against the target database (e.g. ai-rag-db-1).

IF OBJECT_ID(N'dbo.risk_ingest_records', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_ingest_records (
    id              BIGINT        IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    record_uuid     CHAR(36)      NOT NULL,
    review_outcome  VARCHAR(20)   NOT NULL,       -- passed | rejected | frozen
    [text]          NVARCHAR(MAX) NULL,
    metadata        NVARCHAR(MAX) NULL,
    created_at      DATETIME2     NOT NULL CONSTRAINT DF_risk_ingest_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UK_risk_ingest_record_uuid UNIQUE (record_uuid),
    CONSTRAINT CK_risk_ingest_review_outcome CHECK (review_outcome IN ('passed', 'rejected', 'frozen'))
  );
END;
GO

IF NOT EXISTS (
  SELECT 1 FROM sys.indexes
  WHERE name = N'IX_risk_ingest_created_at'
    AND object_id = OBJECT_ID(N'dbo.risk_ingest_records')
)
BEGIN
  CREATE INDEX IX_risk_ingest_created_at ON dbo.risk_ingest_records (created_at);
END;
GO

IF NOT EXISTS (
  SELECT 1 FROM sys.indexes
  WHERE name = N'IX_risk_ingest_review_outcome'
    AND object_id = OBJECT_ID(N'dbo.risk_ingest_records')
)
BEGIN
  CREATE INDEX IX_risk_ingest_review_outcome ON dbo.risk_ingest_records (review_outcome);
END;
GO
