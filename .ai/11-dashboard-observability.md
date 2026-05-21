# 11 — Dashboard & observability

## Health endpoints

| Endpoint | Response | Use |
|----------|----------|-----|
| `GET /health` | `{ ok, db: "azure-sql" }` | K8s/App Service probe |
| App Service default | `/health` or home | Configure in Portal |

## Recommended Azure Monitor setup

| Component | Integration |
|-----------|-------------|
| App Service `ai-rag-webapp` | Application Insights enabled |
| ACA Job | Log Analytics workspace for Container Apps |
| SQL | Azure SQL metrics + Query Store |
| AI Search | Service metrics blade |

## Dashboard layout (Azure Workbook or Grafana)

### Row 1 — API health

| Panel | Query / metric |
|-------|----------------|
| Request rate | App Insights `requests` count by name (`/rag/ingest`, `/rag/assess`) |
| Failed requests | `success == false` |
| p95 latency | `requests` duration |

### Row 2 — Dependencies

| Panel | Dependency type |
|-------|-----------------|
| OpenAI latency | `dependencies` name contains `openai` |
| Search latency | `dependencies` name contains `search` |
| SQL errors | JDBC exceptions in traces |

### Row 3 — Business KPIs (SQL scheduled query → metric)

| Panel | SQL |
|-------|-----|
| Ingests today | `SELECT COUNT(*) FROM risk_ingest_records WHERE created_at >= CAST(GETUTCDATE() AS date)` |
| Assesses (proxy) | Activity log or custom metric from app |
| Decisions mix | `SELECT decision, COUNT(*) FROM risk_decisions GROUP BY decision` |
| Binned coverage | `SELECT COUNT(*) FROM risk_feature_binned` |

### Row 4 — ML pipeline

| Panel | Source |
|-------|--------|
| Last Blob artifact age | Storage blob `models/risk_pipeline_latest.json` LastModified |
| ACA Job last run | Container Apps Job execution status |
| Train failures | ACA logs / GH workflow conclusion |

### Row 5 — Search quality

| Panel | Source |
|-------|--------|
| Index document count | Search REST API |
| Assess zero-hit rate | Custom log field `similarRecords.size==0` |

## Logging standards (implement in code)

| Field | Example |
|-------|---------|
| `correlationId` | UUID per HTTP request |
| `requestId` | Assess internal id |
| `operation` | `ingest`, `assess`, `chat` |
| `durationMs` | |

**Java:** SLF4J structured logs in `IngestService`, `AssessService`.

## Alerts (suggested)

| Alert | Condition |
|-------|-----------|
| API 5xx rate | &gt; 5% over 15 min |
| Health check fail | 2 consecutive failures |
| OpenAI 429/5xx | Dependency failure spike |
| SQL connection errors | Any in 5 min |
| ACA Job failed | Execution status = Failed |

## Non-functional

| NFR | Target |
|-----|--------|
| Log retention | 90 days hot, 1 year archive |
| PII in logs | Redact `metadata` bodies in debug logs |
| Audit | `activity_log` for user actions — not a substitute for App Insights |

## Unit tests (observability)

| Test | Assert |
|------|--------|
| `/health` returns 200 when DB up | `@SpringBootTest` |
| `/health` 503 when DB down | Testcontainers or mock |

**File:** `HealthControllerTest` (generate if missing).
