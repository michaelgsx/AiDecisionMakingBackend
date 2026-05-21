# 05 — ML logistic cascade (3-stage)

**Subsystem ID:** S5  
**Repo:** `AiDecisionMakingML`  
**Code:** `train.py`, `src/risk_pipeline/*`

## Functional requirements

| ID | Requirement |
|----|-------------|
| F5.1 | Load `risk_feature_binned` + `risk_decisions` labels. |
| F5.2 | Stage 1: `reject` vs non-reject (final decision). |
| F5.3 | Stage 2: `freeze` vs pass on non-reject (`ever_freeze` label). |
| F5.4 | Stage 3: `manual_review` (multi-step / human touch heuristic). |
| F5.5 | L2-normalize coefficients per stage; export JSON artifact. |
| F5.6 | Upload to `airagblob` / `logistic` / `models/`. |
| F5.7 | Cascade inference: reject → freeze → manual_review → pass. |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF5.1 | Train on CPU; sklearn `LogisticRegression`, `class_weight=balanced`. |
| NF5.2 | Minimum labeled rows warning (&lt; 8 → holdout skip). |
| NF5.3 | Daily schedule 02:15 UTC (ACA Job or GH Actions). |
| NF5.4 | No training inside ACR Tasks (ACR = image build only). |

## Database (read)

| Source | Use |
|--------|-----|
| `risk_feature_binned` | `onehot_json` features |
| `risk_decisions` | Labels aggregated per `request_id` |
| `risk_feature_bin_calibrations` | `flatten_layout_json` for feature names |

## Blob artifact schema

```json
{
  "version": "v1",
  "trained_at": "ISO-8601",
  "calibration_id": "00000000-0000-0000-0000-000000000001",
  "model_type": "logistic_regression_cascade",
  "stages": {
    "reject_vs_non_reject": { "intercept": 0.0, "weights": {}, "threshold": 0.5 },
    "freeze_vs_pass": { },
    "manual_review": { }
  }
}
```

## Unit tests

| Test | File |
|------|------|
| Structured parse | `AzureOpenAiChatServiceTest` (backend) — N/A here |
| Stage train smoke | `tests` or run `train.py --no-upload` on seed DB |
| Blob upload mock | Mock `azure-storage-blob` |

**Repo tests:** `AzureOpenAiChatServiceTest` equivalent → add `tests/test_logistic_stages.py`.

## Dashboard

| Widget | Source |
|--------|--------|
| Last train time | Blob `risk_pipeline_latest.json` `trained_at` |
| Stage positive rates | Artifact `training_summary` |
| Job success | ACA Job execution history |

## CI/CD

| Workflow | Repo | Trigger |
|----------|------|---------|
| `deploy-aca-daily-train.yml` | ML | push / manual |
| `daily-train.yml` | ML | optional GH runner |

ACA: env `airag-aca-env`, job `ai-rag-ml-daily-train`, image `airagacr.azurecr.io/ai-rag-ml/train:latest`.

See **`10-cicd-and-ops.md`**.

## Future integration (backend)

Load `models/risk_pipeline_latest.json` at assess time → tabular score alongside LLM (not implemented).
