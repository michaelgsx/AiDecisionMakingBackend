#!/usr/bin/env python3
"""
Backfill Azure AI Search index `risk-records` from SQL risk_ingest_records (+ risk_features).

Deduplication:
  - SQL rows: one document per record_uuid (latest created_at wins if duplicates exist)
  - Index: optional skip when id already exists (--skip-existing, default on)
  - Upload uses @search.action=upload with id=record_uuid (same id overwrites)

Requires db/.env: Azure SQL + AZURE_SEARCH_* + AZURE_OPENAI_* (embedding).

Usage:
    cd AiDecisionMakingBackend
    pip install -r db/requirements.txt
    python db/backfill_azure_search.py --dry-run
    python db/backfill_azure_search.py
    python db/backfill_azure_search.py --force --batch-size 50
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from risk_feature_taxonomy import extract_text_blob
from sql_util import DB_DIR, connect, load_project_dotenv

load_project_dotenv()

EMBED_API_VERSION = os.getenv("AZURE_OPENAI_API_VERSION", "2024-02-01")
SEARCH_API_VERSION = os.getenv("AZURE_SEARCH_API_VERSION", "2024-07-01")


def http_json(
    method: str,
    url: str,
    headers: dict[str, str],
    body: dict | list | None = None,
    timeout: int = 180,
) -> Any:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        err = e.read().decode() if e.fp else str(e)
        raise RuntimeError(f"HTTP {e.code} {url}: {err}") from e


def azure_embed(text: str, endpoint: str, api_key: str, deployment: str) -> tuple[list[float], str]:
    base = endpoint.rstrip("/")
    url = f"{base}/openai/deployments/{deployment}/embeddings?api-version={EMBED_API_VERSION}"
    payload = http_json(
        "POST",
        url,
        {"api-key": api_key, "Content-Type": "application/json"},
        {"input": text},
    )
    data = payload.get("data") or []
    if not data:
        raise RuntimeError("Empty embedding response")
    vec = data[0].get("embedding")
    if not isinstance(vec, list):
        raise RuntimeError("Invalid embedding in response")
    return [float(x) for x in vec], str(payload.get("model") or deployment)


def list_index_ids(search_endpoint: str, admin_key: str, index_name: str) -> set[str]:
    """Paginate all document keys in the index."""
    base = search_endpoint.rstrip("/")
    url = f"{base}/indexes/{index_name}/docs/search?api-version={SEARCH_API_VERSION}"
    ids: set[str] = set()
    skip = 0
    page = 1000
    while True:
        body = {
            "search": "*",
            "select": "id",
            "top": page,
            "skip": skip,
            "count": True,
        }
        result = http_json(
            "POST",
            url,
            {"api-key": admin_key, "Content-Type": "application/json"},
            body,
        )
        values = result.get("value") or []
        if not values:
            break
        for doc in values:
            doc_id = doc.get("id")
            if doc_id:
                ids.add(str(doc_id))
        skip += len(values)
        total = result.get("@odata.count")
        if total is not None and skip >= int(total):
            break
        if len(values) < page:
            break
    return ids


def index_documents_batch(
    search_endpoint: str,
    admin_key: str,
    index_name: str,
    documents: list[dict[str, Any]],
) -> dict[str, Any]:
    base = search_endpoint.rstrip("/")
    url = f"{base}/indexes/{index_name}/docs/index?api-version={SEARCH_API_VERSION}"
    envelope = {"value": documents}
    return http_json(
        "POST",
        url,
        {"api-key": admin_key, "Content-Type": "application/json"},
        envelope,
    )


def text_field(meta: dict[str, Any], key: str) -> str:
    v = meta.get(key)
    if v is None:
        return ""
    return str(v).strip()


def build_embed_text(record_id: str, review_outcome: str, case_notes: str, metadata_json: str) -> str:
    notes = (case_notes or "").strip()
    meta = metadata_json if metadata_json and metadata_json.strip() else "{}"
    return (
        f"record_id={record_id}\n"
        f"review_outcome={review_outcome}\n"
        f"case_notes=\n{notes}\n"
        f"metadata_json=\n{meta}"
    )


def parse_metadata(raw: str | None, features_json: str | None) -> str:
    """Prefer risk_features.features_json when present; else ingest metadata column."""
    if features_json and str(features_json).strip():
        return str(features_json).strip()
    if raw and str(raw).strip():
        return str(raw).strip()
    return "{}"


def load_sql_records(cursor) -> list[dict[str, Any]]:
    cursor.execute(
        """
        SELECT
          r.record_uuid,
          r.review_outcome,
          r.[text] AS case_notes,
          r.metadata,
          r.created_at,
          f.features_json
        FROM dbo.risk_ingest_records r
        LEFT JOIN dbo.risk_features f
          ON f.request_id = r.record_uuid AND f.source = 'ingest'
        ORDER BY r.created_at ASC
        """
    )
    rows = []
    for record_uuid, outcome, notes, metadata, created_at, features_json in cursor.fetchall():
        rows.append(
            {
                "record_uuid": str(record_uuid).strip(),
                "review_outcome": (outcome or "").strip(),
                "case_notes": notes,
                "metadata": metadata,
                "created_at": created_at,
                "features_json": features_json,
            }
        )

    if rows:
        return rows

    # Fallback: features-only ingest rows (no risk_ingest_records yet)
    cursor.execute(
        """
        SELECT
          f.request_id,
          COALESCE(JSON_VALUE(f.features_json, '$.reviewOutcome'), 'passed'),
          NULL,
          f.features_json,
          f.created_at,
          f.features_json
        FROM dbo.risk_features f
        WHERE f.source = 'ingest'
        ORDER BY f.created_at ASC
        """
    )
    for record_uuid, outcome, notes, metadata, created_at, features_json in cursor.fetchall():
        rows.append(
            {
                "record_uuid": str(record_uuid).strip(),
                "review_outcome": (outcome or "passed").strip(),
                "case_notes": notes,
                "metadata": metadata,
                "created_at": created_at,
                "features_json": features_json,
            }
        )
    return rows


def dedupe_sql_rows(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    """Keep latest created_at per record_uuid."""
    by_id: dict[str, dict[str, Any]] = {}
    dup_count = 0
    for row in rows:
        rid = row["record_uuid"]
        if not rid:
            continue
        if rid in by_id:
            dup_count += 1
        by_id[rid] = row
    return list(by_id.values()), dup_count


def build_search_document(
    row: dict[str, Any],
    case_vector: list[float],
    text_vector: list[float] | None,
    model_name: str,
) -> dict[str, Any]:
    record_id = row["record_uuid"]
    outcome = row["review_outcome"] or ""
    meta_str = parse_metadata(row.get("metadata"), row.get("features_json"))
    content = build_embed_text(record_id, outcome, row.get("case_notes") or "", meta_str)

    meta_obj: dict[str, Any] = {}
    try:
        parsed = json.loads(meta_str)
        if isinstance(parsed, dict):
            meta_obj = parsed
    except json.JSONDecodeError:
        pass

    doc: dict[str, Any] = {
        "@search.action": "upload",
        "id": record_id,
        "recordId": record_id,
        "reviewOutcome": outcome,
        "caseNotes": (row.get("case_notes") or "").strip(),
        "metadataJson": meta_str,
        "content": content,
        "userId": text_field(meta_obj, "user_id"),
        "scenario": text_field(meta_obj, "scenario"),
        "transactionId": text_field(meta_obj, "transaction_id"),
        "embeddingModel": model_name[:200],
        "embeddingDimensions": len(case_vector),
        "contentVector": case_vector,
    }
    if text_vector:
        doc["textVector"] = text_vector
    return doc


def print_config_help(
    search_endpoint: str,
    search_key: str,
    oai_endpoint: str,
    oai_key: str,
    oai_deploy: str,
) -> None:
    lines = [
        "Missing configuration for backfill. Add these to db/.env (or backend/.env):",
        "",
    ]
    checks = [
        ("AZURE_SEARCH_ENDPOINT", search_endpoint, "https://<search>.search.windows.net"),
        ("AZURE_SEARCH_ADMIN_KEY", search_key, "<Search service → Keys → Primary admin key>"),
        ("AZURE_OPENAI_ENDPOINT", oai_endpoint, "https://<openai-resource>.openai.azure.com"),
        ("AZURE_OPENAI_API_KEY", oai_key, "<OpenAI resource → Keys and Endpoint>"),
        (
            "AZURE_OPENAI_EMBEDDING_DEPLOYMENT",
            oai_deploy,
            "e.g. text-embedding-ada-002 (must match your deployment name)",
        ),
    ]
    for name, value, hint in checks:
        mark = "ok" if value and value.strip() else "MISSING"
        lines.append(f"  [{mark}] {name}")
        if mark == "MISSING":
            lines.append(f"         → {hint}")
    lines.extend(
        [
            "",
            "Copy the same values from Azure App Service → Configuration (Java API app),",
            "or from Key Vault secrets (ai-search, azure-openai-api-key).",
            f"Template: {DB_DIR / '.env.example'}",
        ]
    )
    print("\n".join(lines), file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill risk-records index from SQL.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--skip-existing",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Skip ids already in the search index (default: true)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Upload all SQL rows (overwrites index docs with same id)",
    )
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--limit", type=int, default=0, help="Max rows to process (0=all)")
    parser.add_argument("--sleep-ms", type=int, default=200, help="Pause between embed calls")
    args = parser.parse_args()

    if args.force:
        args.skip_existing = False

    search_endpoint = os.getenv("AZURE_SEARCH_ENDPOINT", "").strip()
    search_key = os.getenv("AZURE_SEARCH_ADMIN_KEY", "").strip()
    index_name = os.getenv("AZURE_SEARCH_INDEX_NAME", "risk-records").strip()

    oai_endpoint = os.getenv("AZURE_OPENAI_ENDPOINT", "").strip()
    oai_key = os.getenv("AZURE_OPENAI_API_KEY", "").strip()
    oai_deploy = os.getenv("AZURE_OPENAI_EMBEDDING_DEPLOYMENT", "").strip()

    if not args.dry_run:
        missing = []
        if not search_endpoint or not search_key:
            missing.append("AZURE_SEARCH_ENDPOINT, AZURE_SEARCH_ADMIN_KEY")
        if not oai_endpoint or not oai_key or not oai_deploy:
            missing.append("AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, AZURE_OPENAI_EMBEDDING_DEPLOYMENT")
        if missing:
            print_config_help(
                search_endpoint,
                search_key,
                oai_endpoint,
                oai_key,
                oai_deploy,
            )
            sys.exit(1)

    conn = connect()
    cursor = conn.cursor()
    sql_rows = load_sql_records(cursor)
    cursor.close()
    conn.close()

    records, sql_dupes = dedupe_sql_rows(sql_rows)
    if args.limit > 0:
        records = records[: args.limit]

    print(f"SQL ingest rows read: {len(sql_rows)} → unique record_uuid: {len(records)}")
    if sql_dupes:
        print(f"  (dropped {sql_dupes} duplicate UUID row(s), kept latest)")

    existing_ids: set[str] = set()
    if not args.dry_run and args.skip_existing:
        print("Listing existing index document ids…")
        existing_ids = list_index_ids(search_endpoint, search_key, index_name)
        print(f"  Index already has {len(existing_ids)} document(s)")

    to_upload = [r for r in records if not args.skip_existing or r["record_uuid"] not in existing_ids]
    skipped_index = len(records) - len(to_upload)
    if skipped_index:
        print(f"Skipping {skipped_index} already indexed (use --force to re-upload)")

    if not to_upload:
        print("Nothing to upload.")
        return

    print(f"Will upload {len(to_upload)} document(s) to index '{index_name}'")

    if args.dry_run:
        for r in to_upload[:5]:
            meta = parse_metadata(r.get("metadata"), r.get("features_json"))
            print(f"  {r['record_uuid'][:8]}… outcome={r['review_outcome']} meta_len={len(meta)}")
        if len(to_upload) > 5:
            print(f"  … and {len(to_upload) - 5} more")
        return

    uploaded = 0
    failed = 0
    batch: list[dict[str, Any]] = []
    batch_size = max(1, min(args.batch_size, 1000))

    for i, row in enumerate(to_upload, 1):
        rid = row["record_uuid"]
        try:
            meta_str = parse_metadata(row.get("metadata"), row.get("features_json"))
            case_input = build_embed_text(
                rid, row["review_outcome"] or "", row.get("case_notes") or "", meta_str
            )
            if args.sleep_ms > 0:
                time.sleep(args.sleep_ms / 1000.0)
            case_vector, model_name = azure_embed(case_input, oai_endpoint, oai_key, oai_deploy)

            text_vector = None
            try:
                meta_obj = json.loads(meta_str) if meta_str else {}
            except json.JSONDecodeError:
                meta_obj = {}
            text_blob = extract_text_blob(meta_obj if isinstance(meta_obj, dict) else {})
            if text_blob:
                if args.sleep_ms > 0:
                    time.sleep(args.sleep_ms / 1000.0)
                text_vector, _ = azure_embed(text_blob, oai_endpoint, oai_key, oai_deploy)

            doc = build_search_document(row, case_vector, text_vector, model_name)
            batch.append(doc)

            if len(batch) >= batch_size or i == len(to_upload):
                result = index_documents_batch(search_endpoint, search_key, index_name, batch)
                uploaded += len(batch)
                errors = [x for x in (result.get("value") or []) if not x.get("status")]
                if errors:
                    failed += len(errors)
                    print(f"  batch partial errors: {errors[:2]}", file=sys.stderr)
                print(f"  indexed {uploaded}/{len(to_upload)}")
                batch = []
        except Exception as e:
            failed += 1
            print(f"  FAIL {rid}: {e}", file=sys.stderr)

    print(f"\nDone. uploaded≈{uploaded - failed}, failed={failed}, skipped_existing={skipped_index}")


if __name__ == "__main__":
    main()
