# Azure AI Search index for ingest / hybrid RAG

## Prerequisites

- Search service: e.g. `https://airagsearchsxgu.search.windows.net`
- Admin key (store in Key Vault, e.g. secret `ai-search`)

## Create the index

`contentVector` is **1536** dimensions — matches common deployments (`text-embedding-ada-002`, `text-embedding-3-small` default). If your embedding model outputs another size (e.g. **3072** for `text-embedding-3-large`), edit `contentVector.dimensions` in `azure_search_index_risk_records.json` **before** creating the index (dimensions cannot be changed in-place; delete/recreate the index if wrong).

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

After ingest + embedding, the API uploads one document per record with lexical fields for **hybrid** search and `contentVector` for vector queries.
