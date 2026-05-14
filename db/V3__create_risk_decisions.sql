-- V3: Create risk_decisions table — audit log of pass / reject / freeze decisions.
-- freeze is non-final; a request may accumulate multiple rows (e.g. freeze → pass).

IF OBJECT_ID(N'dbo.risk_decisions', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_decisions (
    id          BIGINT        IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    request_id  CHAR(36)      NOT NULL,
    decision    VARCHAR(10)   NOT NULL,
    is_final    AS CAST(
                  CASE WHEN decision IN ('pass', 'reject') THEN 1 ELSE 0 END
                AS BIT) PERSISTED,
    reason      NVARCHAR(MAX) NULL,
    decided_by  NVARCHAR(200) NULL,
    created_at  DATETIME2     NOT NULL
      CONSTRAINT DF_risk_decisions_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_risk_decisions_decision CHECK (decision IN ('pass', 'reject', 'freeze')),
    CONSTRAINT FK_risk_decisions_request  FOREIGN KEY (request_id) REFERENCES dbo.risk_features (request_id)
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_decisions_request_created' AND object_id = OBJECT_ID(N'dbo.risk_decisions'))
  CREATE INDEX IX_risk_decisions_request_created ON dbo.risk_decisions (request_id, created_at DESC);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_decisions_decision'        AND object_id = OBJECT_ID(N'dbo.risk_decisions'))
  CREATE INDEX IX_risk_decisions_decision        ON dbo.risk_decisions (decision);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_decisions_non_final'       AND object_id = OBJECT_ID(N'dbo.risk_decisions'))
  CREATE INDEX IX_risk_decisions_non_final       ON dbo.risk_decisions (decision) WHERE decision = 'freeze';
GO
