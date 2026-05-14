#!/usr/bin/env python3
"""
Insert sample data into all risk tables in Azure SQL Database.

Usage:
    cd AiDecisionMakingBackend
    python db/seed_data.py
"""

import json
import os
import re
import sys
import uuid
from pathlib import Path

from dotenv import load_dotenv

DB_DIR = Path(__file__).resolve().parent
load_dotenv(DB_DIR / ".env")


def parse_connection_string(cs: str) -> dict:
    def get(name: str) -> str:
        m = re.search(rf"(?:^|;)\s*{name}\s*=\s*([^;]+)", cs, re.IGNORECASE)
        return m.group(1).strip() if m else ""

    server = get("Server") or get("Data Source")
    server = re.sub(r"^tcp:", "", server, flags=re.IGNORECASE)
    port = 1433
    if "," in server:
        parts = server.rsplit(",", 1)
        server = parts[0].strip()
        try:
            port = int(parts[1].strip())
        except ValueError:
            pass

    return {
        "server": server,
        "port": port,
        "database": get("Initial Catalog") or get("Database"),
        "user": get("User ID") or get("UID"),
        "password": get("Password") or get("Pwd"),
    }


def get_connection_kwargs() -> dict:
    cs = os.getenv("AZURE_SQL_CONNECTION_STRING", "").strip()
    if cs:
        return parse_connection_string(cs)

    server = os.getenv("AZURE_SQL_SERVER", "").strip()
    database = os.getenv("AZURE_SQL_DATABASE", "").strip()
    user = os.getenv("AZURE_SQL_USER", "").strip()
    password = os.getenv("AZURE_SQL_PASSWORD", "")
    port = int(os.getenv("AZURE_SQL_PORT", "1433"))

    if not server or not database or not user:
        print("ERROR: Set connection vars in db/.env", file=sys.stderr)
        sys.exit(1)

    return {"server": server, "port": port, "database": database, "user": user, "password": password}


# ── Sample data ──────────────────────────────────────────────────────

RISK_FEATURES_ROWS = [
    {
        "request_id": str(uuid.uuid4()),
        "source": "ingest",
        "scenario": "withdrawal",
        "transaction_id": f"TXN-{uuid.uuid4().hex[:8].upper()}",
        "user_id": "user-001",
        "device_id": "dev-A1B2C3",
        "country_code": "US",
        "withdraw_amount": 1500.00,
        "deposit_amount": 0,
        "total_amount": 1500.00,
        "features_json": json.dumps({
            "scenario": "withdrawal",
            "transaction_id": f"TXN-SAMPLE1",
            "user_id": "user-001",
            "device_id": "dev-A1B2C3",
            "ip": "203.0.113.42",
            "country_code": "US",
            "withdraw_amount": "1500.00",
            "deposit_amount": "0",
            "total_amount": "1500.00",
            "email_trace": "Subject: Urgent wire transfer request\nFrom: client@example.com\nTo: ops@bank.com\nPlease process the withdrawal immediately.",
            "conversation_trace": "[14:01] Agent: How can I help?\n[14:02] Customer: I need to withdraw $1500 urgently.\n[14:03] Agent: Processing now.",
        }),
    },
    {
        "request_id": str(uuid.uuid4()),
        "source": "ingest",
        "scenario": "deposit",
        "transaction_id": f"TXN-{uuid.uuid4().hex[:8].upper()}",
        "user_id": "user-002",
        "device_id": "dev-X9Y8Z7",
        "country_code": "GB",
        "withdraw_amount": 0,
        "deposit_amount": 25000.00,
        "total_amount": 25000.00,
        "features_json": json.dumps({
            "scenario": "deposit",
            "transaction_id": "TXN-SAMPLE2",
            "user_id": "user-002",
            "device_id": "dev-X9Y8Z7",
            "ip": "198.51.100.10",
            "country_code": "GB",
            "withdraw_amount": "0",
            "deposit_amount": "25000.00",
            "total_amount": "25000.00",
            "email_trace": "Subject: Fund deposit confirmation\nFrom: treasury@corp.co.uk\nDepositing GBP 25,000 from corporate account.",
            "conversation_trace": "[09:30] Bot: Welcome. What would you like to do?\n[09:31] Customer: Deposit £25,000 into my business account.",
        }),
    },
    {
        "request_id": str(uuid.uuid4()),
        "source": "risk-similarity",
        "scenario": "p2p_transfer",
        "transaction_id": f"TXN-{uuid.uuid4().hex[:8].upper()}",
        "user_id": "user-003",
        "device_id": "dev-M4N5O6",
        "country_code": "DE",
        "withdraw_amount": 500.00,
        "deposit_amount": 0,
        "total_amount": 500.00,
        "features_json": json.dumps({
            "scenario": "p2p_transfer",
            "transaction_id": "TXN-SAMPLE3",
            "user_id": "user-003",
            "device_id": "dev-M4N5O6",
            "ip": "192.0.2.77",
            "country_code": "DE",
            "withdraw_amount": "500.00",
            "deposit_amount": "0",
            "total_amount": "500.00",
            "email_trace": "",
            "conversation_trace": "[11:00] Customer: Transfer 500 EUR to my friend.\n[11:01] Agent: Verified. Transfer initiated.",
        }),
    },
    {
        "request_id": str(uuid.uuid4()),
        "source": "ingest",
        "scenario": "withdrawal",
        "transaction_id": f"TXN-{uuid.uuid4().hex[:8].upper()}",
        "user_id": "user-004",
        "device_id": "dev-Q1R2S3",
        "country_code": "JP",
        "withdraw_amount": 80000.00,
        "deposit_amount": 0,
        "total_amount": 80000.00,
        "features_json": json.dumps({
            "scenario": "withdrawal",
            "transaction_id": "TXN-SAMPLE4",
            "user_id": "user-004",
            "device_id": "dev-Q1R2S3",
            "ip": "100.64.0.5",
            "country_code": "JP",
            "withdraw_amount": "80000.00",
            "deposit_amount": "0",
            "total_amount": "80000.00",
            "email_trace": "Subject: Large withdrawal alert\nFrom: alerts@bank.jp\nA withdrawal of ¥80,000 has been flagged for review.",
            "conversation_trace": "[16:45] Customer: I need to pull 80,000 from savings.\n[16:46] Agent: This exceeds the threshold. Initiating manual review.",
        }),
    },
    {
        "request_id": str(uuid.uuid4()),
        "source": "ingest",
        "scenario": "account_opening",
        "transaction_id": f"TXN-{uuid.uuid4().hex[:8].upper()}",
        "user_id": "user-005",
        "device_id": "dev-T7U8V9",
        "country_code": "BR",
        "withdraw_amount": 0,
        "deposit_amount": 100.00,
        "total_amount": 100.00,
        "features_json": json.dumps({
            "scenario": "account_opening",
            "transaction_id": "TXN-SAMPLE5",
            "user_id": "user-005",
            "device_id": "dev-T7U8V9",
            "ip": "10.0.0.99",
            "country_code": "BR",
            "withdraw_amount": "0",
            "deposit_amount": "100.00",
            "total_amount": "100.00",
            "email_trace": "Subject: New account opened\nFrom: no-reply@fintech.com.br\nWelcome! Your account has been created.",
            "conversation_trace": "[08:15] Bot: Let's set up your account.\n[08:16] Customer: Sure, here are my details.",
        }),
    },
]


def main() -> None:
    import pymssql

    kwargs = get_connection_kwargs()
    print(f"Connecting to {kwargs['server']}:{kwargs['port']}/{kwargs['database']} ...")
    conn = pymssql.connect(**kwargs)
    cursor = conn.cursor()

    # ── 1. risk_features ─────────────────────────────────────────────
    print("\n=== Inserting into risk_features ===")
    for row in RISK_FEATURES_ROWS:
        cursor.execute(
            """
            INSERT INTO dbo.risk_features
              (request_id, source, scenario, transaction_id, user_id, device_id,
               country_code, withdraw_amount, deposit_amount, total_amount, features_json)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                row["request_id"], row["source"], row["scenario"],
                row["transaction_id"], row["user_id"], row["device_id"],
                row["country_code"], row["withdraw_amount"], row["deposit_amount"],
                row["total_amount"], row["features_json"],
            ),
        )
        print(f"  + request {row['request_id'][:8]}... ({row['scenario']}, {row['user_id']})")
    conn.commit()

    # ── 2. risk_decisions (references risk_features.request_id) ──────
    print("\n=== Inserting into risk_decisions ===")
    decisions_data = [
        (RISK_FEATURES_ROWS[0]["request_id"], "pass",   "Low risk score; auto-approved.",        "system"),
        (RISK_FEATURES_ROWS[1]["request_id"], "freeze", "Large deposit flagged for compliance.", "compliance-bot"),
        (RISK_FEATURES_ROWS[1]["request_id"], "pass",   "Compliance cleared after review.",      "analyst-jane"),
        (RISK_FEATURES_ROWS[2]["request_id"], "pass",   "Standard P2P transfer.",                "system"),
        (RISK_FEATURES_ROWS[3]["request_id"], "freeze", "Exceeds daily limit; pending review.",  "system"),
        (RISK_FEATURES_ROWS[3]["request_id"], "reject", "Suspicious pattern detected.",          "analyst-bob"),
        (RISK_FEATURES_ROWS[4]["request_id"], "pass",   "New account — initial deposit OK.",     "system"),
    ]
    for req_id, decision, reason, decided_by in decisions_data:
        cursor.execute(
            """
            INSERT INTO dbo.risk_decisions (request_id, decision, reason, decided_by)
            VALUES (%s, %s, %s, %s)
            """,
            (req_id, decision, reason, decided_by),
        )
        print(f"  + {decision:6s} for {req_id[:8]}... by {decided_by}")
    conn.commit()

    # ── 3. risk_ingest_records ───────────────────────────────────────
    print("\n=== Inserting into risk_ingest_records ===")
    ingest_rows = [
        (str(uuid.uuid4()), "passed",   "Withdrawal of $1,500 approved.",                  '{"reviewOutcome":"passed","reviewOutcomeAt":"2026-05-10T10:00:00Z"}'),
        (str(uuid.uuid4()), "rejected", "Suspicious large withdrawal rejected.",            '{"reviewOutcome":"rejected","reviewOutcomeAt":"2026-05-10T11:30:00Z"}'),
        (str(uuid.uuid4()), "frozen",   "Account frozen pending investigation.",            '{"reviewOutcome":"frozen","reviewOutcomeAt":"2026-05-10T12:00:00Z"}'),
        (str(uuid.uuid4()), "passed",   "Deposit of £25,000 cleared by compliance.",        '{"reviewOutcome":"passed","reviewOutcomeAt":"2026-05-11T09:45:00Z"}'),
        (str(uuid.uuid4()), "frozen",   "New account flagged — awaiting document upload.",  '{"reviewOutcome":"frozen","reviewOutcomeAt":"2026-05-12T08:20:00Z"}'),
    ]
    for record_uuid, outcome, text, metadata in ingest_rows:
        cursor.execute(
            """
            INSERT INTO dbo.risk_ingest_records (record_uuid, review_outcome, [text], metadata)
            VALUES (%s, %s, %s, %s)
            """,
            (record_uuid, outcome, text, metadata),
        )
        print(f"  + {outcome:8s} {record_uuid[:8]}...")
    conn.commit()

    # ── 4. risk_embeddings (references risk_features.request_id) ─────
    print("\n=== Inserting into risk_embeddings ===")
    dummy_embedding_8d = [0.12, -0.34, 0.56, 0.78, -0.91, 0.23, -0.45, 0.67]
    for row in RISK_FEATURES_ROWS:
        for etype in ("feature", "conversation"):
            cursor.execute(
                """
                INSERT INTO dbo.risk_embeddings
                  (request_id, embedding_type, embedding_json, dimensions, model_name, model_version)
                VALUES (%s, %s, %s, %s, %s, %s)
                """,
                (
                    row["request_id"],
                    etype,
                    json.dumps(dummy_embedding_8d),
                    len(dummy_embedding_8d),
                    "text-embedding-ada-002",
                    "v2",
                ),
            )
            print(f"  + {etype:14s} for {row['request_id'][:8]}...")
    conn.commit()

    # ── Verify counts ────────────────────────────────────────────────
    print("\n=== Row counts ===")
    for table in ("risk_features", "risk_decisions", "risk_ingest_records", "risk_embeddings"):
        cursor.execute(f"SELECT COUNT(*) FROM dbo.{table}")
        count = cursor.fetchone()[0]
        print(f"  {table}: {count}")

    cursor.close()
    conn.close()
    print("\nSeed data inserted successfully.")


if __name__ == "__main__":
    main()
