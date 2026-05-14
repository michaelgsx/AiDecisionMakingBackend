-- V5: Create activity_log table — tamper-evident audit chain per user.
-- Each row stores a SHA-256 hash built from (prev_hash + row content),
-- forming a per-user blockchain-style chain.

IF OBJECT_ID(N'dbo.activity_log', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.activity_log (
    id              BIGINT        IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    user_id         NVARCHAR(200) NOT NULL,
    transaction_id  NVARCHAR(200) NOT NULL,
    biz_action      VARCHAR(10)   NOT NULL,
    record_action   VARCHAR(10)   NOT NULL,
    prev_hash       VARCHAR(64)   NOT NULL,
    hash_code       VARCHAR(64)   NOT NULL,
    created_at      DATETIME2     NOT NULL
      CONSTRAINT DF_activity_log_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_activity_log_biz_action    CHECK (biz_action    IN ('pass', 'reject', 'freeze')),
    CONSTRAINT CK_activity_log_record_action CHECK (record_action IN ('add', 'delete', 'restore'))
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_activity_log_user_id'        AND object_id = OBJECT_ID(N'dbo.activity_log'))
  CREATE INDEX IX_activity_log_user_id        ON dbo.activity_log (user_id, created_at DESC);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_activity_log_transaction_id'  AND object_id = OBJECT_ID(N'dbo.activity_log'))
  CREATE INDEX IX_activity_log_transaction_id  ON dbo.activity_log (transaction_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_activity_log_biz_action'      AND object_id = OBJECT_ID(N'dbo.activity_log'))
  CREATE INDEX IX_activity_log_biz_action      ON dbo.activity_log (biz_action);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_activity_log_hash_code'       AND object_id = OBJECT_ID(N'dbo.activity_log'))
  CREATE UNIQUE INDEX IX_activity_log_hash_code ON dbo.activity_log (hash_code);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_activity_log_created_at'      AND object_id = OBJECT_ID(N'dbo.activity_log'))
  CREATE INDEX IX_activity_log_created_at      ON dbo.activity_log (created_at);
GO
