#!/usr/bin/env python3
"""
Insert sample activity_log rows with chained SHA-256 hashes.

Hash formula per row:
  hash_code = SHA256(prev_hash | user_id | transaction_id | biz_action | record_action | timestamp)

The chain is per-user: each user's first record uses a genesis prev_hash of 64 zeros.

Usage:
    cd AiDecisionMakingBackend
    python db/seed_activity_log.py
"""

import hashlib
import os
import re
import sys
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
        "server": server, "port": port,
        "database": get("Initial Catalog") or get("Database"),
        "user": get("User ID") or get("UID"),
        "password": get("Password") or get("Pwd"),
    }


def get_connection_kwargs() -> dict:
    cs = os.getenv("AZURE_SQL_CONNECTION_STRING", "").strip()
    if cs:
        return parse_connection_string(cs)
    print("ERROR: Set AZURE_SQL_CONNECTION_STRING in db/.env", file=sys.stderr)
    sys.exit(1)


GENESIS_HASH = "0" * 64


def compute_hash(prev_hash: str, user_id: str, transaction_id: str,
                 biz_action: str, record_action: str, timestamp: str) -> str:
    payload = "|".join([prev_hash, user_id, transaction_id, biz_action, record_action, timestamp])
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


SEED_EVENTS = [
    # (user_id,    transaction_id, biz_action, record_action, timestamp)
    ("user-001", "TXN-00A1", "pass",   "add",     "2026-05-10T10:00:00"),
    ("user-001", "TXN-00A2", "freeze", "add",     "2026-05-10T11:15:00"),
    ("user-001", "TXN-00A2", "pass",   "restore", "2026-05-10T14:30:00"),
    ("user-002", "TXN-00B1", "reject", "add",     "2026-05-10T09:00:00"),
    ("user-002", "TXN-00B2", "pass",   "add",     "2026-05-10T10:30:00"),
    ("user-002", "TXN-00B1", "pass",   "restore", "2026-05-11T08:00:00"),
    ("user-003", "TXN-00C1", "freeze", "add",     "2026-05-11T12:00:00"),
    ("user-003", "TXN-00C1", "reject", "delete",  "2026-05-11T13:45:00"),
    ("user-004", "TXN-00D1", "pass",   "add",     "2026-05-12T07:00:00"),
    ("user-004", "TXN-00D2", "freeze", "add",     "2026-05-12T09:20:00"),
    ("user-004", "TXN-00D2", "reject", "delete",  "2026-05-12T10:00:00"),
    ("user-004", "TXN-00D3", "pass",   "add",     "2026-05-12T11:30:00"),
    ("user-005", "TXN-00E1", "pass",   "add",     "2026-05-13T06:00:00"),
]


def main() -> None:
    import pymssql

    kwargs = get_connection_kwargs()
    print(f"Connecting to {kwargs['server']}:{kwargs['port']}/{kwargs['database']} ...")
    conn = pymssql.connect(**kwargs)
    cursor = conn.cursor()

    user_last_hash: dict[str, str] = {}

    print("\n=== Inserting into activity_log ===")
    for user_id, txn_id, biz, rec, ts in SEED_EVENTS:
        prev = user_last_hash.get(user_id, GENESIS_HASH)
        hc = compute_hash(prev, user_id, txn_id, biz, rec, ts)
        cursor.execute(
            """
            INSERT INTO dbo.activity_log
              (user_id, transaction_id, biz_action, record_action, prev_hash, hash_code, created_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (user_id, txn_id, biz, rec, prev, hc, ts),
        )
        user_last_hash[user_id] = hc
        print(f"  + {user_id} | {txn_id} | {biz:6s} | {rec:7s} | hash={hc[:16]}...")

    conn.commit()

    cursor.execute("SELECT COUNT(*) FROM dbo.activity_log")
    count = cursor.fetchone()[0]
    print(f"\nactivity_log total rows: {count}")

    cursor.close()
    conn.close()
    print("Seed data inserted successfully.")


if __name__ == "__main__":
    main()
