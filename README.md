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
| POST   | /rag/assess                       | Similar cases: Azure OpenAI embed + AI Search hybrid |
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

**App Service:** set `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY` (e.g. Key Vault `azure-openai-api-key`), `AZURE_OPENAI_EMBEDDING_DEPLOYMENT`.

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
export AZURE_SQL_SERVER=ai-rag-sql-server.database.windows.net
export AZURE_SQL_DATABASE=ai-rag-db-1
export AZURE_SQL_USER=youruser
export AZURE_SQL_PASSWORD=yourpass
./mvnw spring-boot:run            # or: mvn spring-boot:run
```

The API starts on port 8787 by default (override with `PORT` env var).

### 3. Point the frontend

In the frontend repo, set:

```
VITE_API_BASE_URL=http://localhost:8787
```
