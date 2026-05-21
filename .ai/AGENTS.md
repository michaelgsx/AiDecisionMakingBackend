# Agent instructions — regenerate this project from design docs

You are implementing the **AI Risk Decision** platform. Follow documents in `.ai/` in order listed in `README.md`.

## Hard rules

1. **Three repos:** Backend (Spring + `db/`), Frontend (Vite React), ML (Python train).
2. **Do not invent** Azure resource names — use `10-cicd-and-ops.md`.
3. **Assess LLM JSON** must match `schemas/assess-llm-response.json` and `02-assess-rag-api.md`.
4. **Feature keys** must match `db/risk_feature_taxonomy.py` and frontend `featureSchema.ts`.
5. **SQL migrations** V1–V7 must match `08-database-schema.md`.
6. **Search index** must match `09-azure-search-index.md` and `db/azure_search_index_risk_records.json`.
7. Never commit real secrets; use `.env.example` only.

## Per-subsystem checklist

When implementing subsystem doc `NN-*.md`, deliver:

- [ ] All **Functional** requirements (F*.*)
- [ ] All **Non-functional** requirements (NF*.)
- [ ] **Database** tables/migrations as specified
- [ ] **Unit tests** listed (create file if missing)
- [ ] **Dashboard** hooks (log/metric names documented)
- [ ] **CI/CD** workflow or document manual step

## Code layout (target)

```
AiDecisionMakingBackend/
  backend/src/main/java/com/aidecision/backend/...
  db/V*.sql, db/*.py
  .ai/                    ← this folder (do not delete)
  .github/workflows/

AiDecisionMakingFrontend/
  src/pages/IngestPage.tsx, AssessPage.tsx
  src/types/api.ts
  .github/workflows/

AiDecisionMakingML/
  train.py, src/risk_pipeline/
  Dockerfile, .github/workflows/
```

## Verification commands

```bash
# Backend
cd backend && ./mvnw test package -DskipTests

# Frontend
npm ci && npm run build

# ML
pip install -r requirements.txt && python -m pytest tests/  # when present

# DB
python db/run_migrations.py
python db/offline_bin_calibration.py --save-db
```

## When unsure

Prefer **reject/freeze** over **pass** in assess prompts. Prefer **structured JSON** over free text for LLM output. Keep top-level assess `reason` as **search summary only**.
