-- V7: Part 2 also includes categorical features → one-hot flatten layout + per-value spot map.
-- Re-run: python db/run_migrations.py && python db/build_quantile_bins.py

IF NOT EXISTS (
  SELECT 1 FROM sys.columns
  WHERE object_id = OBJECT_ID(N'dbo.risk_feature_bin_calibrations')
    AND name = N'categorical_feature_count'
)
BEGIN
  ALTER TABLE dbo.risk_feature_bin_calibrations ADD
    categorical_feature_count INT NOT NULL
      CONSTRAINT DF_risk_bin_cal_cat_count DEFAULT (0),
    categorical_features_json NVARCHAR(MAX) NULL,
    flatten_dim INT NOT NULL
      CONSTRAINT DF_risk_bin_cal_flatten_dim DEFAULT (0),
    flatten_layout_json NVARCHAR(MAX) NULL;
END;
GO

IF NOT EXISTS (
  SELECT 1 FROM sys.columns
  WHERE object_id = OBJECT_ID(N'dbo.risk_feature_bin_edges')
    AND name = N'feature_kind'
)
BEGIN
  ALTER TABLE dbo.risk_feature_bin_edges ADD
    feature_kind VARCHAR(20) NOT NULL
      CONSTRAINT DF_risk_bin_edges_kind DEFAULT ('numeric'),
    encoding_json NVARCHAR(MAX) NULL;
END;
GO

IF NOT EXISTS (
  SELECT 1 FROM sys.columns
  WHERE object_id = OBJECT_ID(N'dbo.risk_feature_binned')
    AND name = N'onehot_json'
)
BEGIN
  ALTER TABLE dbo.risk_feature_binned ADD
    onehot_json NVARCHAR(MAX) NULL,
    active_spots_json NVARCHAR(MAX) NULL,
    flatten_dim INT NULL;
END;
GO
