# AI Decision Making Backend

Java (Spring Boot 3) backend + Python DB migration tooling for the AI RAG risk-review console.

## Project Structure

```
├── db/                          # Database migrations & tooling
│   ├── V1__create_risk_ingest_records.sql   # Table DDL for Azure SQL
│   ├── run_migrations.py        # Python script to execute .sql files remotely
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

| Method | Path           | Description                                 |
|--------|---------------|---------------------------------------------|
| POST   | /rag/ingest   | Save a risk record (Pass / Reject / Freeze) |
| POST   | /rag/assess   | Risk assessment placeholder (RAG pipeline)  |
| GET    | /health       | DB connectivity check                       |

### POST /rag/ingest

```json
{
  "text": "optional case notes",
  "metadata": "{\"scenario\":\"login_anomaly\",...}",
  "reviewOutcome": "passed"       // passed | rejected | frozen
}
```

Response: `{ "ok": true, "recordIndex": 1, "recordId": "uuid", "message": "..." }`

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
