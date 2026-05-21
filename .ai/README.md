# AI Decision Making — Design Documentation

This folder is the **source of truth for AI-assisted code generation**. Each document decomposes the system into implementable workstreams with functional/non-functional requirements, database design, unit tests, dashboards, and CI/CD.

## How to use (for humans and coding agents)

1. Read **`00-system-overview.md`** first (repos, boundaries, data flow).
2. Implement subsystems in dependency order (see table below).
3. For each subsystem file, satisfy **all sections**: Functional, Non-functional, Database, Unit tests, Dashboard, CI/CD.
4. Cross-check **`08-database-schema.md`** and **`schemas/`** before writing SQL or DTOs.
5. Keep **`../db/risk_feature_taxonomy.py`** and **`../../AiDecisionMakingFrontend/src/risk/featureSchema.ts`** in sync when feature keys change.

## Repository map

| Repo | Role | Design docs |
|------|------|-------------|
| `AiDecisionMakingBackend` | Spring API, SQL migrations, offline `db/` jobs | `01`–`06`, `08`–`11` |
| `AiDecisionMakingFrontend` | Vite React SPA (Ingest + Assess) | `07` |
| `AiDecisionMakingML` | Daily logistic train → Blob | `05` (train); ACA in `10` |

## Implementation order (recommended)

| Phase | Doc | Delivers |
|-------|-----|----------|
| 1 | `08-database-schema.md` | Azure SQL tables V1–V7 |
| 2 | `03`, `04` | Offline feature pipeline |
| 3 | `01`, `02` | `/rag/ingest`, `/rag/assess` |
| 4 | `06` | Activity log audit API |
| 5 | `09` | Azure AI Search index + backfill |
| 6 | `07` | Frontend SPA |
| 7 | `05` | ML pipeline + Blob weights |
| 8 | `10`, `11` | CI/CD + observability |

## Document index

| File | Subsystem |
|------|-----------|
| [00-system-overview.md](./00-system-overview.md) | Architecture & decomposition |
| [01-ingest-api.md](./01-ingest-api.md) | POST `/rag/ingest` |
| [02-assess-rag-api.md](./02-assess-rag-api.md) | POST `/rag/assess` + LLM JSON |
| [03-feature-pipeline-part2-bins.md](./03-feature-pipeline-part2-bins.md) | Quantile bins + one-hot |
| [04-feature-pipeline-part3-embeddings.md](./04-feature-pipeline-part3-embeddings.md) | Text embeddings |
| [05-ml-logistic-pipeline.md](./05-ml-logistic-pipeline.md) | 3-stage logistic + Blob |
| [06-activity-log-audit.md](./06-activity-log-audit.md) | Tamper-evident audit log |
| [07-frontend-spa.md](./07-frontend-spa.md) | React UI + SWA deploy |
| [08-database-schema.md](./08-database-schema.md) | All tables & FKs |
| [09-azure-search-index.md](./09-azure-search-index.md) | Hybrid search index |
| [10-cicd-and-ops.md](./10-cicd-and-ops.md) | GitHub Actions, Azure resources |
| [11-dashboard-observability.md](./11-dashboard-observability.md) | Metrics, logs, health |
| [AGENTS.md](./AGENTS.md) | Coding-agent checklist & verification |
| [schemas/assess-llm-response.json](./schemas/assess-llm-response.json) | Assess chat JSON Schema |

## Canonical contracts

- **Assess LLM output:** `schemas/assess-llm-response.json` (also summarized in `02-assess-rag-api.md`).
- **Ingest review outcomes:** `passed` \| `rejected` \| `frozen` (index + SQL).
- **Decision audit outcomes:** `pass` \| `reject` \| `freeze` (`risk_decisions`, activity log).
- **Active bin calibration:** `00000000-0000-0000-0000-000000000001`.
