#!/usr/bin/env python3
"""
Embed natural-language risk features (email_trace, conversation_trace) via Azure OpenAI.

Writes vectors to dbo.risk_embeddings for hybrid search (part 3). Numeric bin vectors
belong in risk_feature_binned / future 'feature' embedding_type — not this script.

Usage:
    cd AiDecisionMakingBackend
    pip install -r db/requirements.txt
    # db/.env: SQL + AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, AZURE_OPENAI_EMBEDDING_DEPLOYMENT
    python db/embed_text_features.py
    python db/embed_text_features.py --limit 100 --types email,conversation,text_combined
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from risk_feature_taxonomy import extract_text_blob
from sql_util import connect, load_project_dotenv

load_project_dotenv()

EMBED_API_VERSION = os.getenv("AZURE_OPENAI_API_VERSION", "2024-02-01")


def azure_embed(text: str, endpoint: str, api_key: str, deployment: str) -> tuple[list[float], str]:
    base = endpoint.rstrip("/")
    url = f"{base}/openai/deployments/{deployment}/embeddings?api-version={EMBED_API_VERSION}"
    body = json.dumps({"input": text}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"api-key": api_key, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        err = e.read().decode() if e.fp else str(e)
        raise RuntimeError(f"Azure OpenAI embedding HTTP {e.code}: {err}") from e

    data = payload.get("data") or []
    if not data:
        raise RuntimeError("Empty embedding response")
    vec = data[0].get("embedding")
    if not isinstance(vec, list):
        raise RuntimeError("Invalid embedding vector in response")
    model = payload.get("model") or deployment
    return [float(x) for x in vec], str(model)


def load_rows(cursor, limit: int | None) -> list[tuple[str, dict[str, Any]]]:
    sql = """
        SELECT request_id, features_json
        FROM dbo.risk_features
        WHERE features_json IS NOT NULL
        ORDER BY created_at
    """
    if limit is not None:
        sql = sql.replace("ORDER BY", f"ORDER BY")  # noqa — top not portable; filter in Python
    cursor.execute(sql)
    out: list[tuple[str, dict[str, Any]]] = []
    for request_id, raw in cursor.fetchall():
        if limit is not None and len(out) >= limit:
            break
        try:
            feats = json.loads(raw) if isinstance(raw, str) else raw
        except json.JSONDecodeError:
            continue
        if isinstance(feats, dict):
            out.append((str(request_id).strip(), feats))
    return out


def upsert_embedding(
    cursor,
    request_id: str,
    embedding_type: str,
    vector: list[float],
    model_name: str,
) -> None:
    cursor.execute(
        """
        DELETE FROM dbo.risk_embeddings
        WHERE request_id = %s AND embedding_type = %s AND model_name = %s
        """,
        (request_id, embedding_type, model_name),
    )
    cursor.execute(
        """
        INSERT INTO dbo.risk_embeddings
          (request_id, embedding_type, embedding_json, dimensions, model_name, model_version)
        VALUES (%s, %s, %s, %s, %s, NULL)
        """,
        (request_id, embedding_type, json.dumps(vector), len(vector), model_name),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="Max rows (0 = all)")
    parser.add_argument(
        "--types",
        default="email,conversation,text_combined",
        help="Comma-separated: email, conversation, text_combined",
    )
    args = parser.parse_args()

    endpoint = os.getenv("AZURE_OPENAI_ENDPOINT", "").strip()
    api_key = os.getenv("AZURE_OPENAI_API_KEY", "").strip()
    deployment = os.getenv("AZURE_OPENAI_EMBEDDING_DEPLOYMENT", "").strip()

    if not endpoint or not api_key or not deployment:
        print(
            "Set AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, "
            "AZURE_OPENAI_EMBEDDING_DEPLOYMENT in db/.env",
            file=sys.stderr,
        )
        sys.exit(1)

    want = {t.strip() for t in args.types.split(",") if t.strip()}
    limit = args.limit if args.limit > 0 else None

    conn = connect()
    cursor = conn.cursor()
    rows = load_rows(cursor, limit)
    print(f"Processing {len(rows)} risk_features rows; embedding types: {sorted(want)}")

    done = 0
    for request_id, feats in rows:
        payloads: list[tuple[str, str]] = []

        if "email" in want:
            email = feats.get("email_trace")
            if email and str(email).strip():
                payloads.append(("email", str(email).strip()))

        if "conversation" in want:
            chat = feats.get("conversation_trace")
            if chat and str(chat).strip():
                payloads.append(("conversation", str(chat).strip()))

        if "text_combined" in want:
            combined = extract_text_blob(feats)
            if combined:
                payloads.append(("text_combined", combined))

        for etype, text in payloads:
            vec, model = azure_embed(text, endpoint, api_key, deployment)
            upsert_embedding(cursor, request_id, etype, vec, model)
            done += 1
            print(f"  {request_id[:8]}… {etype} dim={len(vec)}")

        conn.commit()

    cursor.close()
    conn.close()
    print(f"Done. {done} embeddings upserted into dbo.risk_embeddings.")


if __name__ == "__main__":
    main()
