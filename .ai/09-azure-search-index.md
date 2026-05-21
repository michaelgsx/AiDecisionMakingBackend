# 09 — Azure AI Search (hybrid + dual vector)

**Subsystem ID:** S8  
**Index name:** `risk-records`  
**Definition:** `db/azure_search_index_risk_records.json`

## Functional requirements

| ID | Requirement |
|----|-------------|
| F9.1 | Fields: `content` (lexical), `contentVector`, `textVector` (1536 HNSW cosine). |
| F9.2 | Filterable facets: `userId`, `scenario`, `transactionId`, `reviewOutcome`. |
| F9.3 | Ingest uploads full case + optional NL-only vector. |
| F9.4 | Assess queries multi-vector with weights (`AZURE_SEARCH_VECTOR_WEIGHT_CASE` 0.6, `TEXT` 0.4). |
| F9.5 | `select` parameter comma-separated string (not JSON array). |
| F9.6 | Backfill script `db/backfill_azure_search.py` from SQL. |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF9.1 | Index recreate required when vector schema changes. |
| NF9.2 | Admin key only on server/backfill — never in browser. |
| NF9.3 | API version `2024-07-01` default. |

## Database support

| SQL source | Search field |
|------------|--------------|
| `risk_ingest_records` + `risk_features` | `content`, metadata |
| `risk_embeddings.text_combined` | `textVector` |
| Ingest embedding | `contentVector` |

## Unit tests

| Test | File |
|------|------|
| Query body shape | `AzureSearchQueryServiceTest` |
| Select string format | Regression test for comma-separated |
| Ingest document fields | `IngestServiceTest` / integration |

## Dashboard

| Widget | Portal |
|--------|--------|
| Search latency | Azure AI Search metrics |
| Index document count | Search explorer |
| Query volume | App Insights dependency |

## CI/CD

- Index JSON in repo — apply manually: `az search index create` (see `db/README_AZURE_SEARCH.md`).
- Backfill: run `backfill_azure_search.py` after index recreate.

**Env:** `AZURE_SEARCH_ENDPOINT`, `AZURE_SEARCH_ADMIN_KEY`, `AZURE_SEARCH_INDEX_NAME`.

See **`10-cicd-and-ops.md`**.
