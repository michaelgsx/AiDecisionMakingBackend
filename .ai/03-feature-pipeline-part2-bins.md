# 03 — Feature pipeline Part 2 (quantile bins + one-hot)

**Subsystem ID:** S3  
**Code:** `db/build_quantile_bins.py`, `db/offline_bin_calibration.py`, `db/risk_feature_taxonomy.py`

## Functional requirements

| ID | Requirement |
|----|-------------|
| F3.1 | Read all `risk_features.features_json`. |
| F3.2 | Classify keys: ID (skip), numeric (quantile 5 bins), categorical (one-hot, max 32 cats + `__OTHER__`). |
| F3.3 | Fit calibration; write `risk_feature_bin_calibrations`, `risk_feature_bin_edges`. |
| F3.4 | Apply per-row `onehot_json`, `active_spots_json`, `binned_json`. |
| F3.5 | Default **full replace** of bin tables; calibration_id `00000000-0000-0000-0000-000000000001`. |
| F3.6 | CLI: `offline_bin_calibration.py --save-db`, `--min-samples`, `--daily` N/A (ML only). |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF3.1 | Min 20 samples per feature (configurable); skip sparse features. |
| NF3.2 | Offline job only — not on ingest hot path. |
| NF3.3 | Taxonomy synced with frontend `featureSchema.ts`. |

## Database

Tables: **`08-database-schema.md`** § bin calibration.

## Unit tests

| Test | Approach |
|------|----------|
| Quantile edges monotonic | Python unit test on `fit_quantile_edges` |
| Categorical `__OTHER__` bucket | Unit test `fit_categorical` |
| End-to-end on seed data | Manual `offline_bin_calibration.py --no-upload` |

**Generate:** `db/tests/test_bins.py` with pytest + small fixture JSONL.

## Dashboard

| Widget | Query |
|--------|-------|
| Features calibrated | `SELECT COUNT(*) FROM risk_feature_bin_edges` |
| Flatten dim | `flatten_dim` on active calibration |
| Skipped features | Parse script stdout / log file |

## CI/CD

| Item | Notes |
|------|-------|
| Not in backend JAR deploy | Run manually or scheduled VM/ACA later |
| Optional GH workflow | Add `workflow_dispatch` job `calibrate-bins` |

Document in **`10-cicd-and-ops.md`**.

## Outputs for downstream

- ML trains on `risk_feature_binned.onehot_json` + `risk_decisions` (**`05-ml-logistic-pipeline.md`**).
