# Azure AI Search index for ingest / hybrid RAG

## Prerequisites

- Search service: e.g. `https://airagsearchsxgu.search.windows.net`
- Admin key (store in Key Vault, e.g. secret `ai-search`)

## Create the index

The index defines **two** 1536-dim vector fields (same embedding model deployment for both):

| Field | Purpose |
|-------|---------|
| `contentVector` | Full case blob (notes + metadata) |
| `textVector` | NL only (`email_trace`, `conversation_trace`, case notes) |

If you change embedding model dimensions, update **both** fields in `azure_search_index_risk_records.json` **before** creating the index. To add `textVector` to an existing index, **delete and recreate** the index, then re-run backfill.

**Hybrid query weights** (App Service env or `application.yml`):

- `AZURE_SEARCH_VECTOR_WEIGHT_CASE` (default `0.6`) → `contentVector`
- `AZURE_SEARCH_VECTOR_WEIGHT_TEXT` (default `0.4`) → `textVector`

Weights are normalized when both query vectors are present.

### Option A: REST (curl)

```bash
ENDPOINT="https://airagsearchsxgu.search.windows.net"
ADMIN_KEY="<admin-key>"
API_VER="2024-07-01"

curl -sS -X PUT "$ENDPOINT/indexes/risk-records?api-version=$API_VER" \
  -H "api-key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  --data-binary "@azure_search_index_risk_records.json"
```

### Option B: Azure Portal

Import fields from the JSON or create manually to match field names and the vector profile.

## Backend configuration

Set on App Service (or `.env`):

| Variable | Example |
|----------|---------|
| `AZURE_SEARCH_ENDPOINT` | `https://airagsearchsxgu.search.windows.net` |
| `AZURE_SEARCH_ADMIN_KEY` | Key Vault reference to `ai-search` |
| `AZURE_SEARCH_INDEX_NAME` | `risk-records` |

Optional:

| `AZURE_SEARCH_API_VERSION` | Default `2024-07-01` |
| `AZURE_SEARCH_SKIP` | `true` to skip indexing (local dev) |

After ingest + embedding, the API uploads one document per record with lexical fields plus **`contentVector`** and **`textVector`** (when NL fields exist). Assess uses weighted multi-vector hybrid search.

## Backfill from SQL (deduplicated)

If records exist in `dbo.risk_ingest_records` but are missing from the index:

```bash
# db/.env: SQL + AZURE_SEARCH_* + AZURE_OPENAI_* (same as App Service)
python db/backfill_azure_search.py --dry-run
python db/backfill_azure_search.py
python db/backfill_azure_search.py --force          # re-embed & overwrite all ids
python db/backfill_azure_search.py --no-skip-existing  # same as --force for skip logic
```

Dedup rules:

- SQL: one row per `record_uuid` (latest `created_at` wins)
- Index: by default skips document `id` already present; `--force` re-uploads all
- Upload action is `upload` on `id` = `record_uuid` (overwrites same key)
