# 01 — Ingest API (`POST /rag/ingest`)

**Subsystem ID:** S1  
**Code:** `IngestController`, `IngestService`, `AzureOpenAiEmbeddingService`, `AzureSearchIngestService`

## Functional requirements

| ID | Requirement |
|----|-------------|
| F1.1 | Accept `text`, `metadata` (JSON string), `reviewOutcome` (`passed`/`rejected`/`frozen`). |
| F1.2 | Generate UUID `record_uuid`; use as `risk_features.request_id`. |
| F1.3 | Merge metadata; build embedding blob (notes + outcome + metadata). |
| F1.4 | Call Azure OpenAI embedding → 1536-dim vector. |
| F1.5 | Persist `risk_features` (source=`ingest`), `risk_ingest_records`, `risk_embeddings` (type `feature`). |
| F1.6 | After DB commit, upsert Azure AI Search document (`content`, `contentVector`, optional `textVector`). |
| F1.7 | Return `{ ok, recordId, recordIndex, message }`. |
| F1.8 | If `user_id` in metadata, append `activity_log` row (non-blocking on failure). |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF1.1 | Transactional SQL insert before Search upload. |
| NF1.2 | Idempotent Search key = `record_uuid`. |
| NF1.3 | Skip embedding when `AZURE_OPENAI_SKIP_EMBEDDING=true`. |
| NF1.4 | Skip Search when `AZURE_SEARCH_SKIP=true`. |
| NF1.5 | Validate `reviewOutcome` enum; 400 on invalid body. |

## Database

| Table | Operation |
|-------|-----------|
| `risk_features` | INSERT |
| `risk_ingest_records` | INSERT |
| `risk_embeddings` | INSERT `embedding_type=feature` |
| `activity_log` | INSERT (conditional) |

See **`08-database-schema.md`**.

## Unit tests

| Test | File | Description |
|------|------|-------------|
| Valid ingest persists rows | `IngestServiceTest` | Mock embed + search |
| Invalid outcome rejected | Controller integration | 400 |
| Skip flags bypass external calls | `IngestServiceTest` | |
| Activity log on user_id | `IngestServiceTest` | verify `ActivityLogService` |

**Generate when missing:** Mockito tests for dual-vector ingest (`contentVector` + `textVector` weights).

## Dashboard

| Widget | Source |
|--------|--------|
| Ingest count / hour | App Insights custom metric or SQL count by `created_at` |
| Ingest failures | Exception logs tagged `IngestService` |
| Search upload failures | Log + alert on `AzureSearchIngestService` errors |
| Embedding latency | Dependency track OpenAI |

See **`11-dashboard-observability.md`**.

## CI/CD

| Pipeline | Path | Notes |
|----------|------|-------|
| Backend deploy | `.github/workflows/deploy-backend.yml` | Push `v1` + `backend/**` |
| Env on App Service | Portal / Key Vault | OpenAI + Search + SQL |

See **`10-cicd-and-ops.md`**.

## API contract

```json
// Request
{ "text": "...", "metadata": "{...}", "reviewOutcome": "passed" }

// Response
{ "ok": true, "recordId": "uuid", "recordIndex": 1, "message": "..." }
```
