# 10 — CI/CD and operations

## Repositories & branches

| Repo | Default branch | Primary deploy |
|------|----------------|----------------|
| AiDecisionMakingBackend | `v1` | App Service `ai-rag-webapp` |
| AiDecisionMakingFrontend | `v1` | Static Web Apps |
| AiDecisionMakingML | `v1` | ACR + ACA Job |

## GitHub Actions matrix

### Backend (`AiDecisionMakingBackend`)

| Workflow | File | Trigger | Secrets / vars |
|----------|------|---------|----------------|
| Deploy Java API | `.github/workflows/deploy-backend.yml` | push `v1`, `backend/**` | `AZURE_CREDENTIALS` |

**Build:** `./mvnw clean package -DskipTests`  
**Deploy:** `azure/webapps-deploy@v3` → `ai-rag-webapp`

### Frontend (`AiDecisionMakingFrontend`)

| Workflow | File | Trigger | Secrets |
|----------|------|---------|---------|
| SWA deploy (primary) | `.github/workflows/deploy-frontend-swa.yml` | push `v1` | `AZURE_CREDENTIALS`, `VITE_API_BASE_URL`, `VITE_OPS_TOKEN`, Key Vault |
| SWA (generated) | `.github/workflows/azure-static-web-apps-*.yml` | push | `AZURE_STATIC_WEB_APPS_API_TOKEN_*` |
| Node API (legacy) | `.github/workflows/deploy-api-appservice.yml` | optional | Publish profile |

### ML (`AiDecisionMakingML`)

| Workflow | File | Trigger | Secrets |
|----------|------|---------|---------|
| ACA deploy + schedule | `.github/workflows/deploy-aca-daily-train.yml` | push / manual | `AZURE_CREDENTIALS`, `AZURE_SQL_*`, `AZURE_STORAGE_ACCOUNT_KEY` |
| GH runner train (optional) | `.github/workflows/daily-train.yml` | manual only | Same SQL + Blob |

## Azure resource catalog

| Resource | Name | Purpose |
|----------|------|---------|
| Resource group | `ai-rag-rg-1` (typical) | Contains RG assets |
| SQL server | `ai-rag-sql-server.database.windows.net` | |
| SQL database | `ai-rag-db-1` | |
| App Service | `ai-rag-webapp` | Spring Boot API |
| Static Web App | e.g. `mango-desert-0bc0f121e` | Frontend |
| Key Vault | `ai-rag-key` | Secrets |
| OpenAI | `ai-reg-embedding` | Embed + chat |
| AI Search | `airagsearchsxgu` | Index `risk-records` |
| ACR | `airagacr` | ML images |
| Storage | `airagblob` / container `logistic` | Model weights |
| ACA environment | `airag-aca-env` | Job host |
| ACA Job | `ai-rag-ml-daily-train` | Cron `15 2 * * *` |

## App Service configuration (required)

Set in Portal → Configuration (or Key Vault references):

- JDBC / `AZURE_SQL_*`
- `CORS_ORIGINS` = SWA origin URL
- `OPS_TOKEN`
- `AZURE_OPENAI_*`, `AZURE_SEARCH_*`
- `AZURE_OPENAI_CHAT_DEPLOYMENT` must match portal deployment name

## Offline jobs (not CI by default)

| Job | Command | When |
|-----|---------|------|
| Migrations | `python db/run_migrations.py` | New env |
| Bin calibration | `python db/offline_bin_calibration.py --save-db` | Data drift |
| Text embed | `python db/embed_text_features.py` | New text rows |
| Search backfill | `python db/backfill_azure_search.py` | Index rebuild |

## Non-functional (ops)

| NFR | Practice |
|-----|----------|
| Secrets rotation | Key Vault + GH secrets quarterly |
| Blue/green | App Service slots (optional) |
| Rollback | Redeploy prior JAR from GH commit |
| DR | SQL geo-redundant backup; Search index export JSON in repo |

## Unit tests in CI (recommended addition)

```yaml
# Suggested step in deploy-backend.yml before deploy:
- run: ./mvnw test -B
  working-directory: backend
```

Frontend:

```yaml
- run: npm ci && npm run build
```

ML:

```yaml
- run: pip install -r requirements.txt && pytest tests/
```
