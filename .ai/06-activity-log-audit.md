# 06 — Activity log (tamper-evident audit)

**Subsystem ID:** S6  
**Code:** `ActivityLogController`, `ActivityLogService`, entity `ActivityLog`

## Functional requirements

| ID | Requirement |
|----|-------------|
| F6.1 | `POST /audit/log` append with hash chain per `user_id`. |
| F6.2 | `GET /audit/log` list; filter by user / transaction. |
| F6.3 | `GET /audit/log/verify/{userId}` return `{ chainValid: boolean }`. |
| F6.4 | Auto-append on ingest/assess when metadata contains `user_id`. |
| F6.5 | `biz_action`: pass/reject/freeze; `record_action`: add/delete/restore. |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF6.1 | SHA-256 chain: `hash_code = SHA256(prev_hash + payload)`. |
| NF6.2 | Audit write failure must not fail ingest/assess. |
| NF6.3 | Append-only semantics (no update/delete API). |

## Database

`activity_log` — **`08-database-schema.md`**.

## Unit tests

| Test | File |
|------|------|
| Chain valid after 3 appends | `ActivityLogServiceTest` (generate) |
| Tamper detection | Corrupt hash → verify false |
| First row prev_hash sentinel | Unit test |

## Dashboard

| Widget | Query |
|--------|-------|
| Appends per hour | `COUNT(*) GROUP BY hour` |
| Verify failures | API metric on `chainValid=false` |

## CI/CD

Deployed with backend JAR — **`10-cicd-and-ops.md`**.
