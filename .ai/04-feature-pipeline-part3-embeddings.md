# 04 — Feature pipeline Part 3 (text embeddings)

**Subsystem ID:** S4  
**Code:** `db/embed_text_features.py`, `TextFeatureSupport` (Java mirror for ingest/assess)

## Functional requirements

| ID | Requirement |
|----|-------------|
| F4.1 | Extract `email_trace`, `conversation_trace` from `features_json`. |
| F4.2 | Embed via Azure OpenAI; store in `risk_embeddings`. |
| F4.3 | Types: `email`, `conversation`, `text_combined` (default for Search text vector). |
| F4.4 | Upsert unique on `(request_id, embedding_type, model_name)`. |
| F4.5 | CLI `--limit`, `--types` for batch control. |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF4.1 | 1536 dimensions (`text-embedding-ada-002` or configured deployment). |
| NF4.2 | Rate-limit aware batching for large backfills. |
| NF4.3 | Idempotent re-run safe (overwrite same key). |

## Database

`risk_embeddings` — see **`08-database-schema.md`**.

## Unit tests

| Test | Approach |
|------|----------|
| `extract_text_blob` parity | Compare Python vs Java `TextFeatureSupport` sample fixtures |
| Empty text skip | No row inserted |

**Generate:** snapshot tests for combined blob format.

## Dashboard

| Widget | SQL |
|--------|-----|
| Embeddings by type | `GROUP BY embedding_type` |
| Missing text_combined | Features without row for type |

## CI/CD

Offline script; secrets in `db/.env` — **`10-cicd-and-ops.md`**.

Dual-vector Search backfill: `db/backfill_azure_search.py` — **`09-azure-search-index.md`**.
