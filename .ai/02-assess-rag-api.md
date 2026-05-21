# 02 — Assess / RAG API (`POST /rag/assess`)

**Subsystem ID:** S2  
**Code:** `AssessController`, `AssessService`, `AzureSearchQueryService`, `AzureOpenAiChatService`

## Functional requirements

| ID | Requirement |
|----|-------------|
| F2.1 | Accept `text`, `metadata` (JSON); require at least one non-empty. |
| F2.2 | Embed query: dual vectors when text present (`contentVector` + `textVector`, weights 0.6/0.4 default). |
| F2.3 | Hybrid search top-K similar records from index `risk-records`. |
| F2.4 | Return `similarRecords` with scores, metadata, snippets, `readableText`. |
| F2.5 | Return search summary in top-level `reason` (not LLM). |
| F2.6 | When chat configured, call LLM with structured JSON → map to `aiLabel`, `aiReasoning`, `aiEvidence`, `aiConfidence`, `aiKeyRiskFactors`, `aiReason`. |
| F2.7 | Map `aiLabel` to `risk` heuristic (`rejected`→high, `frozen`→medium, `passed`→low). |
| F2.8 | Chat failure → still return search results; `ai*` fields null. |
| F2.9 | Optional activity_log when `user_id` present. |

## Non-functional requirements

| ID | Requirement |
|----|-------------|
| NF2.1 | Chat `temperature=0.15`, `max_tokens=4096`, `response_format=json_object`. |
| NF2.2 | User message capped ~120k chars (truncate with warn). |
| NF2.3 | Parse validates `label` enum and non-empty `reasoning.synthesis`. |
| NF2.4 | Legacy top-level `reason` in LLM JSON still accepted. |
| NF2.5 | p95 latency &lt; 15s with chat (target). |

## LLM response JSON

**Canonical schema:** [`schemas/assess-llm-response.json`](./schemas/assess-llm-response.json)

```json
{
  "label": "rejected",
  "confidence": 0.82,
  "key_risk_factors": ["..."],
  "reasoning": {
    "retrieval_and_scores": "...",
    "feature_comparison": "...",
    "narrative_alignment": "...",
    "historical_decisions": "...",
    "synthesis": "..."
  },
  "evidence": {
    "summary": "...",
    "items": [
      {
        "kind": "similar_case",
        "record_id": "uuid",
        "similarity_score": 0.91,
        "review_outcome": "rejected",
        "claim": "...",
        "quote": "...",
        "supports_label": "rejected"
      }
    ]
  }
}
```

## HTTP response mapping

| LLM / Search | `AssessResponse` field |
|--------------|----------------------|
| Search | `risk`, `reason`, `similarRecords` |
| `label` | `aiLabel` |
| `reasoning` | `aiReasoning` (camelCase) |
| `evidence` | `aiEvidence` |
| `confidence` | `aiConfidence` |
| `key_risk_factors` | `aiKeyRiskFactors` |
| formatted sections | `aiReason` |

**DTOs:** `dto/AssessResponse.java`, `AiAssessDecision.java`, `AiAssessReasoning.java`, `AiAssessEvidence.java`

## Database

Read-only for assess path (no mandatory writes). Optional `activity_log` INSERT.

## Unit tests

| Test | File |
|------|------|
| Search-only assess | `AssessServiceTest` |
| Chat maps label to risk | `AssessServiceTest` |
| Structured JSON parse | `AzureOpenAiChatServiceTest` |
| Legacy `reason` parse | `AzureOpenAiChatServiceTest` |
| Evidence items parse | `AzureOpenAiChatServiceTest` |
| Invalid label throws | `AzureOpenAiChatServiceTest` |
| Select comma-separated | `AzureSearchQueryServiceTest` |

## Dashboard

| Widget | Metric |
|--------|--------|
| Assess QPS | App Insights |
| Search latency | Dependency Azure Search |
| Chat latency / token usage | OpenAI dependency |
| Chat failure rate | Warn logs `Assess chat step failed` |
| Zero-hit assess | Count `similarRecords` empty |

## CI/CD

Same backend workflow as S1 — **`10-cicd-and-ops.md`**.

Frontend displays assess UI — **`07-frontend-spa.md`**.
