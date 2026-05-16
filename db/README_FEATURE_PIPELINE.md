# Risk feature pipeline (3 parts)

Offline jobs read `dbo.risk_features.features_json` (same shape as the ingest UI).

| Part | Feature kinds | Offline script | Storage |
|------|----------------|----------------|---------|
| 1 — IDs | `transaction_id`, `user_id`, `device_id`, … | *(filtered)* | not used for bins / NL embeddings |
| 2 — Numeric + categorical | amounts/counts/booleans → 5 quantile bins; `country_code`, `channel`, … → one-hot | `build_quantile_bins.py` | `risk_feature_bin_*`, `risk_feature_binned` |
| 3 — Text | `email_trace`, `conversation_trace` | `embed_text_features.py` | `risk_embeddings` (`email`, `conversation`, `text_combined`) |

Taxonomy lives in `risk_feature_taxonomy.py` (kept in sync with frontend `featureSchema.ts`).

## Setup

```bash
cd AiDecisionMakingBackend
pip install -r db/requirements.txt
cp db/.env.example db/.env   # SQL + optional Azure OpenAI for embeddings
python db/run_migrations.py  # V6–V7 bin tables + embedding types
```

## Part 2 — Numeric bins + categorical one-hot (flattened)

**Recommended (analyze + export JSON, then save):**

```bash
python db/offline_bin_calibration.py              # report + db/out/bin_calibration/<id>/
python db/offline_bin_calibration.py --save-db    # full overwrite of bin tables (default)
```

Each `--save-db` run **replaces** all rows in `risk_feature_bin_calibrations`, `risk_feature_bin_edges`, and `risk_feature_binned` (no history). Default `calibration_id` is `00000000-0000-0000-0000-000000000001`. Use `--append` only if you need multiple calibrations side by side.

**Direct fit (no analysis export):**

```bash
python db/build_quantile_bins.py
# optional: --n-bins 5 --min-samples 20 --max-categories 32 --calibration-id <uuid>
```

Writes:

- **`risk_feature_bin_calibrations`** — `flatten_layout_json`: global segment order (offset/size per feature); `flatten_dim`
- **`risk_feature_bin_edges`** — one row per encoded feature:
  - **numeric** (`feature_kind=numeric`): `edges_json` + `encoding_json.onehot_offset` / `value_to_onehot_spot` via `bin_0..bin_4`
  - **categorical** (`feature_kind=categorical`): `encoding_json` with `categories`, `value_to_local_index`, **`value_to_onehot_spot`** (each category value → global index in flattened vector)
- **`risk_feature_binned`** — per `request_id`:
  - `onehot_json` — full flattened 0/1 vector (length `flatten_dim`) for training / hybrid tabular side
  - `active_spots_json` — e.g. `"country_code|US": 2`, `"withdraw_amount|bin_3": 11`
  - `binned_json` — human-readable (category string + numeric bin index)
  - `vector_json` — numeric bin indices only (legacy compact form)

Train your model on **`onehot_json`** (or export to numpy from SQL). With default overwrite, always use calibration_id `00000000-0000-0000-0000-000000000001` at scoring time.

## Part 3 — Text embeddings (hybrid search)

```bash
# db/.env: AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, AZURE_OPENAI_EMBEDDING_DEPLOYMENT
python db/embed_text_features.py
python db/embed_text_features.py --limit 50 --types text_combined
```

Upserts into **`risk_embeddings`**:

| `embedding_type` | Source |
|------------------|--------|
| `email` | `email_trace` only |
| `conversation` | `conversation_trace` only |
| `text_combined` | both fields (good default for one vector in Azure AI Search) |

Part 2 binned vectors are **not** embeddings yet; you can later add `embedding_type = 'feature'` from `vector_json` or train directly on bin indices.

## Next steps (your roadmap)

1. Train a model on `risk_feature_binned.vector_json` (+ decision labels).
2. Index `text_combined` (or dual vectors) in Azure AI Search for hybrid retrieval with the tabular model score.
3. Wire online ingest to re-apply the **same** `calibration_id` bin edges and refresh embeddings.
