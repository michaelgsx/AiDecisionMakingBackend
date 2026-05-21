# 07 — Frontend SPA (Ingest + Assess)

**Subsystem ID:** S7  
**Repo:** `AiDecisionMakingFrontend`  
**Stack:** Vite, React, TypeScript

## Functional requirements

| ID | Requirement |
|----|-------------|
| F7.1 | Routes: `/` Ingest, `/assess` Assess. |
| F7.2 | `RiskFeaturesPanel` — structured fields → `features_json` aligned with `featureSchema.ts`. |
| F7.3 | `ingestRecord()` → `POST /rag/ingest` with `reviewOutcome`. |
| F7.4 | `assessRecord()` → `POST /rag/assess`. |
| F7.5 | Assess UI: risk pill, AI label/confidence, key risks, reasoning sections, evidence list, search summary, similar records. |
| F7.6 | Mock mode via `VITE_USE_MOCK=true`. |
| F7.7 | Bearer `VITE_OPS_TOKEN` when set. |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF7.1 | Static build → Azure Static Web Apps. |
| NF7.2 | SPA fallback `staticwebapp.config.json`. |
| NF7.3 | No secrets in repo; `VITE_*` from GitHub Secrets. |
| NF7.4 | Accessible form labels; error surfaces for API failures. |

## Database

None direct — all via Backend API.

## Unit tests

| Test | Tool |
|------|------|
| Type-check | `npm run build` (tsc) |
| API types mirror backend | Manual diff `types/api.ts` vs `AssessResponse.java` |

**Generate:** Vitest for `parseFeatureKeys`, mock client responses.

## Dashboard

| Widget | Source |
|--------|--------|
| SWA availability | Azure Portal SWA metrics |
| Client errors | Browser console / App Insights JS (optional) |

## CI/CD

| Workflow | Trigger |
|----------|---------|
| `deploy-frontend-swa.yml` | `v1` push, `workflow_dispatch` |
| `azure-static-web-apps-*.yml` | Azure-generated |

**Secrets:** `VITE_API_BASE_URL`, `VITE_OPS_TOKEN`, `AZURE_CREDENTIALS`, Key Vault `ai-rag-key`.

See **`10-cicd-and-ops.md`**.

## Type contract

Mirror `AssessResponse` including `aiReasoning`, `aiEvidence` — see **`02-assess-rag-api.md`**.
