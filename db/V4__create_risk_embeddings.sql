-- V4: Create risk_embeddings table — vector embeddings per request.
-- A request may have multiple embedding types (feature, conversation, graph)
-- and may be re-embedded with different models over time.

IF OBJECT_ID(N'dbo.risk_embeddings', N'U') IS NULL
BEGIN
  CREATE TABLE dbo.risk_embeddings (
    id              BIGINT        IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    request_id      CHAR(36)      NOT NULL,
    embedding_type  VARCHAR(30)   NOT NULL,
    embedding_json  NVARCHAR(MAX) NOT NULL,
    dimensions      INT           NOT NULL,
    model_name      NVARCHAR(200) NOT NULL,
    model_version   NVARCHAR(50)  NULL,
    created_at      DATETIME2     NOT NULL
      CONSTRAINT DF_risk_embeddings_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_risk_embeddings_type CHECK (embedding_type IN ('feature', 'conversation', 'graph')),
    CONSTRAINT FK_risk_embeddings_request FOREIGN KEY (request_id) REFERENCES dbo.risk_features (request_id),
    CONSTRAINT UK_risk_embeddings_request_type_model UNIQUE (request_id, embedding_type, model_name)
  );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_embeddings_request_id' AND object_id = OBJECT_ID(N'dbo.risk_embeddings'))
  CREATE INDEX IX_risk_embeddings_request_id ON dbo.risk_embeddings (request_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_embeddings_type'       AND object_id = OBJECT_ID(N'dbo.risk_embeddings'))
  CREATE INDEX IX_risk_embeddings_type       ON dbo.risk_embeddings (embedding_type);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_embeddings_model'      AND object_id = OBJECT_ID(N'dbo.risk_embeddings'))
  CREATE INDEX IX_risk_embeddings_model      ON dbo.risk_embeddings (model_name);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_risk_embeddings_created_at' AND object_id = OBJECT_ID(N'dbo.risk_embeddings'))
  CREATE INDEX IX_risk_embeddings_created_at ON dbo.risk_embeddings (created_at);
GO
