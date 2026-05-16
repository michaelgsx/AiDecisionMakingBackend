"""
Feature taxonomy aligned with frontend src/risk/featureSchema.ts.

Three buckets for the ML / hybrid-search pipeline:
  - id:          identifiers & opaque strings — excluded from part 2
  - numeric:     amounts, counts, booleans — quantile-binned (5 bins) offline
  - categorical: country, channel, device_type, … — one-hot in flattened part-2 vector
  - text:        free-form NL (email, chat) — embedded via Azure OpenAI (part 3)
"""

from __future__ import annotations

import re
from typing import Any, Literal

FeatureKind = Literal["id", "numeric", "text", "categorical"]

# Explicit ID / opaque keys (never binned, not in numeric training vector).
ID_FEATURE_KEYS: frozenset[str] = frozenset(
    {
        "transaction_id",
        "user_id",
        "device_id",
        "merchant_id",
        "device_fingerprint",
        "scenario",  # use-case label, not a continuous signal
        "login_time",
        "timestamp",
        "assessQueryAt",
        "_previousMetadata",
    }
)

# Natural-language fields → embedding pipeline (part 3).
TEXT_FEATURE_KEYS: frozenset[str] = frozenset(
    {
        "email_trace",
        "conversation_trace",
    }
)

# Low-cardinality fields → one-hot in part 2 (flattened with numeric bins).
CATEGORICAL_FEATURE_KEYS: frozenset[str] = frozenset(
    {
        "country_code",
        "device_type",
        "currency",
        "channel",
        "payment_method",
    }
)

# Known numeric keys from the product schema (part 2).
NUMERIC_FEATURE_KEYS: frozenset[str] = frozenset(
    {
        "withdraw_amount",
        "deposit_amount",
        "total_amount",
        "account_age_days",
        "velocity_24h_txn_count",
        "geo_distance_km",
        "failed_login_count_24h",
        "mfa_passed",
        "is_new_device",
        "beneficiary_changed",
    }
)

NUMERIC_KEY_PATTERN = re.compile(
    r"(_amount|_count|_km|_days|_24h)$|^(withdraw_amount|total_amount|deposit_amount)$"
)

ID_KEY_PATTERN = re.compile(
    r"(_id|_uuid|_fingerprint)$|^(ip_address|user_agent)$",
    re.IGNORECASE,
)

CATEGORICAL_KEY_PATTERN = re.compile(
    r"^(country_code|device_type|currency|channel|payment_method|scenario)$",
    re.IGNORECASE,
)


def normalize_category(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    s = str(value).strip()
    return s if s else None


def coerce_number(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        if isinstance(value, float) and (value != value):  # NaN
            return None
        return float(value)
    if isinstance(value, str):
        t = value.strip().replace(",", "")
        if not t:
            return None
        tl = t.lower()
        if tl == "true":
            return 1.0
        if tl == "false":
            return 0.0
        try:
            return float(t)
        except ValueError:
            return None
    return None


def classify_feature(key: str, value: Any) -> FeatureKind:
    k = key.strip()
    if not k:
        return "categorical"

    if k in ID_FEATURE_KEYS or ID_KEY_PATTERN.search(k):
        return "id"

    if k in TEXT_FEATURE_KEYS:
        return "text"

    if k in CATEGORICAL_FEATURE_KEYS or CATEGORICAL_KEY_PATTERN.search(k):
        return "categorical"

    n = coerce_number(value)
    if n is not None:
        if k in NUMERIC_FEATURE_KEYS or NUMERIC_KEY_PATTERN.search(k):
            return "numeric"
        # short numeric strings (e.g. "3") on unknown keys → treat as categorical
        if isinstance(value, str) and len(value.strip()) <= 32:
            return "categorical"
        return "numeric"

    if k in NUMERIC_FEATURE_KEYS or NUMERIC_KEY_PATTERN.search(k):
        return "numeric"

    if isinstance(value, str) and len(value.strip()) > 80:
        return "text"

    if isinstance(value, str) and len(value.strip()) <= 64:
        return "categorical"

    return "categorical"


def extract_text_blob(features: dict[str, Any]) -> str | None:
    """Combine NL fields for a single hybrid-search document."""
    parts: list[str] = []
    for key in sorted(TEXT_FEATURE_KEYS):
        raw = features.get(key)
        if raw is None:
            continue
        text = str(raw).strip()
        if not text:
            continue
        label = "Email" if key == "email_trace" else "Conversation"
        parts.append(f"[{label}]\n{text}")
    if not parts:
        return None
    return "\n\n".join(parts)
