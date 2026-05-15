# AI Decision Making Backend

Java (Spring Boot 3) backend + Python DB migration tooling for the AI RAG risk-review console.

## Project Structure

```
├── db/                          # Database migrations & tooling
│   ├── V1__create_risk_ingest_records.sql
│   ├── V2__create_risk_features.sql
│   ├── V3__create_risk_decisions.sql
│   ├── V4__create_risk_embeddings.sql
│   ├── V5__create_activity_log.sql       # Tamper-evident audit chain
│   ├── azure_search_index_risk_records.json # Azure AI Search index (hybrid + vector)
│   ├── README_AZURE_SEARCH.md           # Create index + env vars
│   ├── run_migrations.py        # Python script to execute .sql files remotely
│   ├── seed_data.py             # Insert sample data into all tables
│   ├── seed_activity_log.py     # Insert chained-hash audit log samples
│   ├── requirements.txt         # Python dependencies (pymssql, python-dotenv)
│   └── .env.example             # Connection config template
│
├── backend/                     # Spring Boot 3 API
│   ├── pom.xml
│   ├── .env.example
│   └── src/main/java/com/aidecision/backend/
│       ├── BackendApplication.java
│       ├── config/              # CORS, OpsToken filter
│       ├── controller/          # REST endpoints
│       ├── dto/                 # Request/response records
│       ├── entity/              # JPA entities
│       ├── repository/          # Spring Data JPA repos
│       └── service/             # Business logic
```

## API Endpoints

| Method | Path                              | Description                                      |
|--------|----------------------------------|--------------------------------------------------|
| POST   | /rag/ingest                       | Save a risk record (Pass / Reject / Freeze)      |
| POST   | /rag/assess                       | Similar cases (AI Search) + optional chat label (`aiLabel` / `aiReason`) |
| GET    | /health                           | DB connectivity check                            |
| POST   | /audit/log                        | Append an activity log entry (chained hash)      |
| GET    | /audit/log                        | List all activity log entries                     |
| GET    | /audit/log/user/{userId}          | Activity log entries for a user                  |
| GET    | /audit/log/transaction/{txnId}    | Activity log entries for a transaction           |
| GET    | /audit/log/verify/{userId}        | Verify hash chain integrity for a user           |

### POST /rag/ingest

```json
{
  "text": "optional case notes",
  "metadata": "{\"scenario\":\"login_anomaly\",...}",
  "reviewOutcome": "passed"       // passed | rejected | frozen
}
```

Response: `{ "ok": true, "recordIndex": 1, "recordId": "uuid", "message": "…; embedding 1536-dim; Azure AI Search indexed" }`

Ingest pipeline:

1. Merges `metadata` and calls **Azure OpenAI** embeddings on a blob built from `record_id`, `review_outcome`, case notes, and merged metadata JSON (before any DB insert).
2. Inserts **`risk_features`** using the same UUID as `record_uuid`, `source=ingest`, denormalized columns from metadata when present.
3. Inserts **`risk_ingest_records`**.
4. Inserts **`risk_embeddings`** (`embedding_type=feature`, JSON vector, dimensions, model name).
5. After the DB transaction commits, uploads the same record to **Azure AI Search** (`risk-records` index): lexical `content` + `contentVector` for hybrid search (skipped if `AZURE_OPENAI_SKIP_EMBEDDING=true` or `AZURE_SEARCH_SKIP=true`).

**Activity log (auto):** On successful **`POST /rag/ingest`** and **`POST /rag/assess`** (after search runs for assess), the server appends one **`activity_log`** row per request **when merged metadata contains `user_id`** (same key as risk features). `transaction_id` is taken from metadata when set; otherwise `ingest:{recordId}` or the assess internal `assess-…` id. Ingest maps `reviewOutcome` → `biz_action` (`passed`→`pass`, `rejected`→`reject`, `frozen`→`freeze`, `record_action`=`add`). Assess uses `biz_action`=`pass` and `record_action`=`add` to mean “assessment API ran”. Failures to write the audit row are logged only and do not fail the API. Explicit **`POST /audit/log`** remains available for other events.

**App Service:** set `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY` (e.g. Key Vault `azure-openai-api-key`), `AZURE_OPENAI_EMBEDDING_DEPLOYMENT`. For **assess**, also set `AZURE_OPENAI_CHAT_DEPLOYMENT` (e.g. `gpt-4o`) so `/rag/assess` can return `aiLabel` / `aiReason`; use `AZURE_OPENAI_SKIP_CHAT=true` to run retrieval only.

**Azure AI Search:** after creating index `risk-records` (see `db/README_AZURE_SEARCH.md`), set `AZURE_SEARCH_ENDPOINT`, `AZURE_SEARCH_ADMIN_KEY` (Key Vault `ai-search`), `AZURE_SEARCH_INDEX_NAME`. Ingest uploads the same embedding + lexical `content` for hybrid search. Use `AZURE_SEARCH_SKIP=true` only for local dev without a search service.

## Quick Start

### 1. Create the database table

```bash
cd db
pip install -r requirements.txt
cp .env.example .env              # fill in Azure SQL credentials
python run_migrations.py
```

### 2. Run the Java backend

Requires Java 17+ and Maven.

```bash
cd backend
cp .env.example .env   # then edit: SQL, AZURE_OPENAI_API_KEY, both deployment names, search key, etc.
./mvnw spring-boot:run
```

Spring Boot loads variables from `backend/.env` (or repo-root `.env` when you run from the monorepo root) **before** `application.yml`, without exporting them in the shell. OS environment variables and Azure App Service **Application settings** still override the file.

Alternatively you can keep using `export AZURE_SQL_…` in the shell as before.

The API starts on port 8787 by default (override with `PORT` env var).

### 3. Point the frontend

In the frontend repo, set:

```
VITE_API_BASE_URL=http://localhost:8787
```
