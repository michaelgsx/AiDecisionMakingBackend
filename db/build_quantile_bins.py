#!/usr/bin/env python3
"""
Offline part-2 encodings for dbo.risk_features:
  - numeric: 5 quantile bins (~equal frequency) per feature
  - categorical: vocabulary + global one-hot spot map
  - flattened one-hot vector per request (numeric = one-hot per bin slot)

Stores calibration, per-feature edges/encoding, and per-row binned + onehot vectors.

Usage:
    python db/run_migrations.py   # V6 + V7
    python db/build_quantile_bins.py   # default: DELETE all bin rows, then insert (overwrite)
    python db/build_quantile_bins.py --append --calibration-id <uuid>  # keep other calibrations
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from risk_feature_taxonomy import (
    ID_FEATURE_KEYS,
    classify_feature,
    coerce_number,
    normalize_category,
)
from sql_util import connect

DEFAULT_N_BINS = 5
DEFAULT_MIN_SAMPLES = 20
DEFAULT_MAX_CATEGORIES = 32
OTHER_LABEL = "__OTHER__"

# Single active calibration when using default full-table replace (overwrite).
ACTIVE_CALIBRATION_ID = "00000000-0000-0000-0000-000000000001"


@dataclass
class FlattenSegment:
    feature: str
    kind: str  # categorical | numeric_bin
    offset: int
    size: int
    categories: list[str] | None = None


@dataclass
class CategoricalFit:
    categories: list[str]
    value_to_local: dict[str, int]
    counts: dict[str, int]


def load_feature_rows(cursor) -> list[tuple[str, dict[str, Any]]]:
    cursor.execute(
        """
        SELECT request_id, features_json
        FROM dbo.risk_features
        WHERE features_json IS NOT NULL
        ORDER BY created_at
        """
    )
    rows: list[tuple[str, dict[str, Any]]] = []
    for request_id, raw in cursor.fetchall():
        try:
            obj = json.loads(raw) if isinstance(raw, str) else raw
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            rows.append((str(request_id).strip(), obj))
    return rows


def collect_columns(
    feature_rows: list[tuple[str, dict[str, Any]]],
) -> tuple[dict[str, list[float]], dict[str, list[str]], set[str], set[str]]:
    numeric_values: dict[str, list[float]] = defaultdict(list)
    categorical_values: dict[str, list[str]] = defaultdict(list)
    id_keys: set[str] = set()
    numeric_keys: set[str] = set()
    categorical_keys: set[str] = set()

    for _, feats in feature_rows:
        for key, val in feats.items():
            kind = classify_feature(key, val)
            if kind == "id":
                id_keys.add(key)
                continue
            if kind == "numeric":
                n = coerce_number(val)
                if n is not None:
                    numeric_keys.add(key)
                    numeric_values[key].append(n)
                continue
            if kind == "categorical":
                cat = normalize_category(val)
                if cat is not None:
                    categorical_keys.add(key)
                    categorical_values[key].append(cat)

    return dict(numeric_values), dict(categorical_values), id_keys, numeric_keys | categorical_keys


def fit_quantile_edges(values: list[float], n_bins: int) -> tuple[list[float], list[int]]:
    arr = np.asarray(values, dtype=np.float64)
    arr = arr[np.isfinite(arr)]
    if arr.size == 0:
        raise ValueError("no finite values")

    if arr.size < n_bins:
        vmin, vmax = float(arr.min()), float(arr.max())
        edges = [vmin + (vmax - vmin) * i / n_bins for i in range(n_bins + 1)]
    else:
        pct = np.linspace(0, 100, n_bins + 1)
        edges = [float(x) for x in np.percentile(arr, pct, method="linear")]

    for i in range(1, len(edges)):
        if edges[i] < edges[i - 1]:
            edges[i] = edges[i - 1]
    edges[-1] = max(edges[-1], float(arr.max()))

    counts = [0] * n_bins
    for x in arr:
        counts[assign_bin(float(x), edges, n_bins)] += 1
    return edges, counts


def assign_bin(value: float, edges: list[float], n_bins: int) -> int:
    cutpoints = edges[1:-1]
    if not cutpoints:
        return 0
    idx = int(np.searchsorted(cutpoints, value, side="right"))
    return min(max(idx, 0), n_bins - 1)


def fit_categorical(values: list[str], max_categories: int) -> CategoricalFit:
    counts = Counter(values)
    # reserve one slot for __OTHER__
    keep_n = max(1, max_categories - 1)
    top = [v for v, _ in counts.most_common(keep_n)]
    categories = top + [OTHER_LABEL]
    value_to_local = {v: i for i, v in enumerate(top)}
    value_to_local[OTHER_LABEL] = len(top)
    return CategoricalFit(
        categories=categories,
        value_to_local=value_to_local,
        counts=dict(counts),
    )


def resolve_category(cat: str, fit: CategoricalFit) -> int:
    if cat in fit.value_to_local:
        return fit.value_to_local[cat]
    return fit.value_to_local[OTHER_LABEL]


def build_flatten_layout(
    categorical_fits: dict[str, CategoricalFit],
    numeric_features: list[str],
    n_bins: int,
) -> tuple[list[FlattenSegment], int]:
    segments: list[FlattenSegment] = []
    offset = 0

    for feat in sorted(categorical_fits.keys()):
        fit = categorical_fits[feat]
        size = len(fit.categories)
        segments.append(
            FlattenSegment(feat, "categorical", offset, size, list(fit.categories))
        )
        offset += size

    for feat in numeric_features:
        segments.append(FlattenSegment(feat, "numeric_bin", offset, n_bins, None))
        offset += n_bins

    return segments, offset


def encode_row(
    feats: dict[str, Any],
    categorical_fits: dict[str, CategoricalFit],
    edges_by_feature: dict[str, list[float]],
    n_bins: int,
    segments: list[FlattenSegment],
    flatten_dim: int,
) -> tuple[dict[str, Any], list[int], list[int], dict[str, int]]:
    """Returns binned_json, legacy vector_json (numeric bin indices), onehot, active_spots."""
    binned: dict[str, Any] = {}
    numeric_vector: list[int] = []
    onehot = [0] * flatten_dim
    active_spots: dict[str, int] = {}

    for seg in segments:
        if seg.kind == "categorical":
            fit = categorical_fits[seg.feature]
            raw = feats.get(seg.feature)
            cat = normalize_category(raw)
            if cat is None:
                continue
            local = resolve_category(cat, fit)
            binned[seg.feature] = cat
            binned[f"{seg.feature}__index"] = local
            global_idx = seg.offset + local
            onehot[global_idx] = 1
            active_spots[f"{seg.feature}|{cat}"] = global_idx
            active_spots[f"{seg.feature}|__onehot_{local}"] = global_idx
        else:
            edges = edges_by_feature.get(seg.feature)
            if not edges:
                continue
            n = coerce_number(feats.get(seg.feature))
            if n is None:
                continue
            local = assign_bin(n, edges, n_bins)
            binned[seg.feature] = local
            numeric_vector.append(local)
            global_idx = seg.offset + local
            onehot[global_idx] = 1
            active_spots[f"{seg.feature}|bin_{local}"] = global_idx

    return binned, numeric_vector, onehot, active_spots


def persist_calibration(
    cursor,
    calibration_id: str,
    n_bins: int,
    training_row_count: int,
    id_features: list[str],
    numeric_features: list[str],
    categorical_features: list[str],
    segments: list[FlattenSegment],
    flatten_dim: int,
    edges_by_feature: dict[str, tuple[list[float], list[int]]],
    categorical_fits: dict[str, CategoricalFit],
) -> None:
    layout = [
        {
            "feature": s.feature,
            "kind": s.kind,
            "offset": s.offset,
            "size": s.size,
            "categories": s.categories,
        }
        for s in segments
    ]

    cursor.execute(
        """
        INSERT INTO dbo.risk_feature_bin_calibrations
          (calibration_id, n_bins, strategy, training_row_count, numeric_feature_count,
           id_features_json, numeric_features_json,
           categorical_feature_count, categorical_features_json,
           flatten_dim, flatten_layout_json)
        VALUES (%s, %s, 'quantile+categorical_onehot', %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (
            calibration_id,
            n_bins,
            training_row_count,
            len(numeric_features),
            json.dumps(id_features),
            json.dumps(numeric_features),
            len(categorical_features),
            json.dumps(categorical_features),
            flatten_dim,
            json.dumps(layout),
        ),
    )

    for feat, (edges, counts) in edges_by_feature.items():
        seg = next(s for s in segments if s.feature == feat and s.kind == "numeric_bin")
        encoding = {
            "kind": "numeric_bin",
            "n_bins": n_bins,
            "onehot_offset": seg.offset,
            "onehot_width": seg.size,
            "spot_names": [f"bin_{i}" for i in range(n_bins)],
        }
        cursor.execute(
            """
            INSERT INTO dbo.risk_feature_bin_edges
              (calibration_id, feature_name, feature_kind, edges_json, bin_counts_json,
               sample_size, encoding_json)
            VALUES (%s, %s, 'numeric', %s, %s, %s, %s)
            """,
            (
                calibration_id,
                feat,
                json.dumps(edges),
                json.dumps(counts),
                sum(counts),
                json.dumps(encoding),
            ),
        )

    for feat, fit in categorical_fits.items():
        seg = next(s for s in segments if s.feature == feat and s.kind == "categorical")
        value_to_global = {
            v: seg.offset + local for v, local in fit.value_to_local.items()
        }
        encoding = {
            "kind": "categorical",
            "categories": fit.categories,
            "value_to_local_index": fit.value_to_local,
            "value_to_onehot_spot": value_to_global,
            "value_counts": fit.counts,
            "onehot_offset": seg.offset,
            "onehot_width": seg.size,
        }
        counts_list = [fit.counts.get(c, 0) for c in fit.categories if c != OTHER_LABEL]
        cursor.execute(
            """
            INSERT INTO dbo.risk_feature_bin_edges
              (calibration_id, feature_name, feature_kind, edges_json, bin_counts_json,
               sample_size, encoding_json)
            VALUES (%s, %s, 'categorical', '[]', %s, %s, %s)
            """,
            (
                calibration_id,
                feat,
                json.dumps(counts_list),
                sum(fit.counts.values()),
                json.dumps(encoding),
            ),
        )


def apply_encodings(
    cursor,
    calibration_id: str,
    feature_rows: list[tuple[str, dict[str, Any]]],
    categorical_fits: dict[str, CategoricalFit],
    edges_by_feature: dict[str, list[float]],
    n_bins: int,
    segments: list[FlattenSegment],
    flatten_dim: int,
    *,
    upsert_rows: bool = True,
) -> int:
    written = 0
    for request_id, feats in feature_rows:
        binned, numeric_vector, onehot, active_spots = encode_row(
            feats, categorical_fits, edges_by_feature, n_bins, segments, flatten_dim
        )
        if not binned:
            continue
        if upsert_rows:
            cursor.execute(
                "DELETE FROM dbo.risk_feature_binned WHERE request_id = %s AND calibration_id = %s",
                (request_id, calibration_id),
            )
        cursor.execute(
            """
            INSERT INTO dbo.risk_feature_binned
              (request_id, calibration_id, binned_json, vector_json,
               onehot_json, active_spots_json, flatten_dim)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            (
                request_id,
                calibration_id,
                json.dumps(binned),
                json.dumps(numeric_vector),
                json.dumps(onehot),
                json.dumps(active_spots),
                flatten_dim,
            ),
        )
        written += 1
    return written


def clear_calibration(cursor, calibration_id: str) -> None:
    cursor.execute(
        "DELETE FROM dbo.risk_feature_binned WHERE calibration_id = %s",
        (calibration_id,),
    )
    cursor.execute(
        "DELETE FROM dbo.risk_feature_bin_edges WHERE calibration_id = %s",
        (calibration_id,),
    )
    cursor.execute(
        "DELETE FROM dbo.risk_feature_bin_calibrations WHERE calibration_id = %s",
        (calibration_id,),
    )


def replace_all_bin_tables(cursor) -> None:
    """Full overwrite: empty all bin tables (FK-safe order)."""
    cursor.execute("DELETE FROM dbo.risk_feature_binned")
    cursor.execute("DELETE FROM dbo.risk_feature_bin_edges")
    cursor.execute("DELETE FROM dbo.risk_feature_bin_calibrations")


def prepare_bin_persist(cursor, calibration_id: str, *, replace_all: bool) -> None:
    if replace_all:
        replace_all_bin_tables(cursor)
    else:
        clear_calibration(cursor, calibration_id)


def resolve_calibration_id(explicit: str, *, replace_all: bool) -> str:
    cid = explicit.strip()
    if cid:
        return cid
    if replace_all:
        return ACTIVE_CALIBRATION_ID
    return str(uuid.uuid4())


def main() -> None:
    parser = argparse.ArgumentParser(description="Fit numeric bins + categorical one-hot layout.")
    parser.add_argument("--calibration-id", default="")
    parser.add_argument("--n-bins", type=int, default=DEFAULT_N_BINS)
    parser.add_argument("--min-samples", type=int, default=DEFAULT_MIN_SAMPLES)
    parser.add_argument("--max-categories", type=int, default=DEFAULT_MAX_CATEGORIES)
    parser.add_argument("--no-apply", action="store_true")
    parser.add_argument(
        "--append",
        action="store_true",
        help="Keep other calibrations; only replace rows for --calibration-id (default: full overwrite)",
    )
    args = parser.parse_args()

    replace_all = not args.append
    calibration_id = resolve_calibration_id(args.calibration_id, replace_all=replace_all)
    n_bins = max(2, args.n_bins)
    min_samples = max(5, args.min_samples)

    conn = connect()
    cursor = conn.cursor()

    feature_rows = load_feature_rows(cursor)
    if not feature_rows:
        print("No rows in dbo.risk_features.", file=sys.stderr)
        sys.exit(1)

    numeric_values, categorical_values, id_keys, _ = collect_columns(feature_rows)
    print(f"Loaded {len(feature_rows)} rows.")

    edges_by_feature: dict[str, tuple[list[float], list[int]]] = {}
    skipped_num: list[str] = []
    for key, vals in sorted(numeric_values.items()):
        if len(vals) < min_samples:
            skipped_num.append(f"{key}(n={len(vals)})")
            continue
        edges, counts = fit_quantile_edges(vals, n_bins)
        edges_by_feature[key] = (edges, counts)
        print(f"  [numeric] {key}: bins={counts}")

    categorical_fits: dict[str, CategoricalFit] = {}
    skipped_cat: list[str] = []
    for key, vals in sorted(categorical_values.items()):
        if len(vals) < min_samples:
            skipped_cat.append(f"{key}(n={len(vals)})")
            continue
        fit = fit_categorical(vals, args.max_categories)
        categorical_fits[key] = fit
        print(f"  [categorical] {key}: K={len(fit.categories)} top={fit.categories[:6]}")

    if skipped_num:
        print("Skipped numeric:", ", ".join(skipped_num))
    if skipped_cat:
        print("Skipped categorical:", ", ".join(skipped_cat))

    numeric_features = sorted(edges_by_feature.keys())
    categorical_features = sorted(categorical_fits.keys())

    if not numeric_features and not categorical_features:
        print("Nothing to fit.", file=sys.stderr)
        sys.exit(1)

    segments, flatten_dim = build_flatten_layout(categorical_fits, numeric_features, n_bins)
    print(f"\nFlatten dim = {flatten_dim} ({len(segments)} segments)")
    for s in segments:
        print(f"  offset {s.offset:3d} size {s.size:2d}  {s.kind:12s}  {s.feature}")

    edges_only = {k: v[0] for k, v in edges_by_feature.items()}
    if replace_all:
        print(f"Replace-all: clearing bin tables, calibration_id={calibration_id}")
    prepare_bin_persist(cursor, calibration_id, replace_all=replace_all)

    persist_calibration(
        cursor,
        calibration_id,
        n_bins,
        len(feature_rows),
        sorted(set(ID_FEATURE_KEYS) | id_keys),
        numeric_features,
        categorical_features,
        segments,
        flatten_dim,
        edges_by_feature,
        categorical_fits,
    )
    conn.commit()
    print(f"\nCalibration {calibration_id} saved.")

    if not args.no_apply:
        n = apply_encodings(
            cursor,
            calibration_id,
            feature_rows,
            categorical_fits,
            edges_only,
            n_bins,
            segments,
            flatten_dim,
            upsert_rows=not replace_all,
        )
        conn.commit()
        print(f"Wrote {n} rows to dbo.risk_feature_binned (onehot_json length {flatten_dim}).")

    cursor.close()
    conn.close()


if __name__ == "__main__":
    main()
