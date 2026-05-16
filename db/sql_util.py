"""Shared Azure SQL helpers for offline db/*.py scripts."""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

from dotenv import load_dotenv

DB_DIR = Path(__file__).resolve().parent
BACKEND_DIR = DB_DIR.parent / "backend"


def load_project_dotenv() -> None:
    """Load db/.env first, then backend/.env for keys not set in db/.env."""
    load_dotenv(DB_DIR / ".env")
    load_dotenv(BACKEND_DIR / ".env", override=False)


load_project_dotenv()


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
        print(
            "ERROR: Set AZURE_SQL_CONNECTION_STRING or AZURE_SQL_SERVER + "
            "AZURE_SQL_DATABASE + AZURE_SQL_USER + AZURE_SQL_PASSWORD in db/.env",
            file=sys.stderr,
        )
        sys.exit(1)

    return {
        "server": server,
        "port": port,
        "database": database,
        "user": user,
        "password": password,
    }


def connect():
    import pymssql

    kwargs = get_connection_kwargs()
    return pymssql.connect(**kwargs)
