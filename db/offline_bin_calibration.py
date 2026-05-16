#!/usr/bin/env python3
"""
Offline: analyze dbo.risk_features → fit quantile bins + categorical vocab → report + optional SQL.

  1. Read features from Azure SQL (or a local JSONL dump)
  2. Print per-feature stats and proposed bin edges
  3. Export JSON under --export-dir (default: db/out/bin_calibration)
  4. With --save-db: full overwrite of risk_feature_bin_* (default; no historical calibrations)

Setup:
    cd AiDecisionMakingBackend
    pip install -r db/requirements.txt
    cp db/.env.example db/.env   # AZURE_SQL_* required for SQL mode
    python db/run_migrations.py  # V6 + V7

Examples:
    python db/offline_bin_calibration.py
    python db/offline_bin_calibration.py --min-samples 10
    python db/offline_bin_calibration.py --save-db
    python db/offline_bin_calibration.py --save-db --no-apply
    python db/offline_bin_calibration.py --from-jsonl db/out/features_dump.jsonl
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_quantile_bins import (  # noqa: E402
    ACTIVE_CALIBRATION_ID,
    DEFAULT_MAX_CATEGORIES,
    DEFAULT_MIN_SAMPLES,
    DEFAULT_N_BINS,
    apply_encodings,
    build_flatten_layout,
    collect_columns,
    fit_categorical,
    fit_quantile_edges,
    load_feature_rows,
    persist_calibration,
    prepare_bin_persist,
    resolve_calibration_id,
)
from sql_util import connect

DB_DIR = Path(__file__).resolve().parent
DEFAULT_EXPORT_DIR = DB_DIR / "out" / "bin_calibration"


def load_from_jsonl(path: Path) -> list[tuple[str, dict[str, Any]]]:
    rows: list[tuple[str, dict[str, Any]]] = []
    with path.open(encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as e:
                print(f"  skip line {line_no}: invalid JSON ({e})", file=sys.stderr)
                continue
            request_id = str(obj.get("request_id") or obj.get("id") or f"row_{line_no}").strip()
            feats = obj.get("features") or obj.get("features_json") or obj
            if not isinstance(feats, dict):
                print(f"  skip line {line_no}: no features dict", file=sys.stderr)
                continue
            rows.append((request_id, feats))
    return rows


def numeric_summary(values: list[float]) -> dict[str, Any]:
    arr = np.asarray(values, dtype=np.float64)
    arr = arr[np.isfinite(arr)]
    if arr.size == 0:
        return {"count": 0}
    return {
        "count": int(arr.size),
        "min": float(arr.min()),
        "max": float(arr.max()),
        "mean": float(arr.mean()),
        "std": float(arr.std()) if arr.size > 1 else 0.0,
        "p25": float(np.percentile(arr, 25)),
        "p50": float(np.percentile(arr, 50)),
        "p75": float(np.percentile(arr, 75)),
    }


def balance_ratio(bin_counts: list[int]) -> float | None:
    if not bin_counts or sum(bin_counts) == 0:
        return None
    nonzero = [c for c in bin_counts if c > 0]
    if not nonzero:
        return None
    return round(max(nonzero) / min(nonzero), 3)


def analyze_features(
    feature_rows: list[tuple[str, dict[str, Any]]],
    n_bins: int,
    min_samples: int,
    max_categories: int,
) -> dict[str, Any]:
    numeric_values, categorical_values, id_keys, _ = collect_columns(feature_rows)

    numeric_report: dict[str, Any] = {}
    skipped_numeric: list[dict[str, str]] = []
    fitted_numeric: dict[str, tuple[list[float], list[int]]] = {}

    for key in sorted(numeric_values.keys()):
        vals = numeric_values[key]
        summary = numeric_summary(vals)
        entry: dict[str, Any] = {"summary": summary, "status": "ok"}
        if summary.get("count", 0) < min_samples:
            entry["status"] = "skipped"
            entry["reason"] = f"count {summary.get('count', 0)} < min_samples {min_samples}"
            skipped_numeric.append({"feature": key, "reason": entry["reason"]})
            numeric_report[key] = entry
            continue
        edges, counts = fit_quantile_edges(vals, n_bins)
        fitted_numeric[key] = (edges, counts)
        entry["edges"] = edges
        entry["bin_counts"] = counts
        entry["balance_ratio"] = balance_ratio(counts)
        numeric_report[key] = entry

    categorical_report: dict[str, Any] = {}
    skipped_categorical: list[dict[str, str]] = []
    fitted_categorical: dict[str, Any] = {}

    for key in sorted(categorical_values.keys()):
        vals = categorical_values[key]
        n = len(vals)
        entry: dict[str, Any] = {"sample_size": n, "status": "ok"}
        if n < min_samples:
            entry["status"] = "skipped"
            entry["reason"] = f"count {n} < min_samples {min_samples}"
            skipped_categorical.append({"feature": key, "reason": entry["reason"]})
            categorical_report[key] = entry
            continue
        fit = fit_categorical(vals, max_categories)
        fitted_categorical[key] = fit
        top_counts = sorted(fit.counts.items(), key=lambda x: -x[1])[:12]
        top_set = {c for c in fit.categories if c != "__OTHER__"}
        other_n = sum(1 for v in vals if v not in top_set)
        entry["categories"] = fit.categories
        entry["value_counts_top"] = top_counts
        entry["other_bucket_rate"] = round(other_n / max(n, 1), 4)
        categorical_report[key] = entry

    numeric_features = sorted(fitted_numeric.keys())
    categorical_features = sorted(fitted_categorical.keys())
    segments, flatten_dim = build_flatten_layout(
        fitted_categorical, numeric_features, n_bins
    )

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "training_row_count": len(feature_rows),
        "n_bins": n_bins,
        "min_samples": min_samples,
        "max_categories": max_categories,
        "id_features": sorted(id_keys),
        "numeric_features_fitted": numeric_features,
        "categorical_features_fitted": categorical_features,
        "skipped_numeric": skipped_numeric,
        "skipped_categorical": skipped_categorical,
        "flatten_dim": flatten_dim,
        "flatten_layout": [
            {
                "feature": s.feature,
                "kind": s.kind,
                "offset": s.offset,
                "size": s.size,
                "categories": s.categories,
            }
            for s in segments
        ],
        "numeric": numeric_report,
        "categorical": categorical_report,
        "_fitted_numeric": fitted_numeric,
        "_fitted_categorical": fitted_categorical,
        "_segments": segments,
    }


def print_report(report: dict[str, Any]) -> None:
    print("=" * 72)
    print("BIN CALIBRATION ANALYSIS")
    print("=" * 72)
    print(f"Rows:              {report['training_row_count']}")
    print(f"Numeric fitted:    {len(report['numeric_features_fitted'])}")
    print(f"Categorical fit:   {len(report['categorical_features_fitted'])}")
    print(f"Flatten dim:       {report['flatten_dim']}")
    print(f"ID keys (ignored): {', '.join(report['id_features'][:8]) or '(none)'}")
    if len(report["id_features"]) > 8:
        print(f"                   ... +{len(report['id_features']) - 8} more")

    print("\n--- Numeric (quantile bins) ---")
    for feat in report["numeric_features_fitted"]:
        r = report["numeric"][feat]
        s = r["summary"]
        edges = r["edges"]
        counts = r["bin_counts"]
        br = r.get("balance_ratio")
        print(
            f"  {feat}: n={s['count']}  min={s['min']:.4g}  max={s['max']:.4g}  "
            f"p50={s['p50']:.4g}  balance={br}"
        )
        print(f"    edges:  {[round(x, 6) for x in edges]}")
        print(f"    counts: {counts}")

    for skip in report["skipped_numeric"]:
        print(f"  [SKIP] {skip['feature']}: {skip['reason']}")

    print("\n--- Categorical (one-hot) ---")
    for feat in report["categorical_features_fitted"]:
        r = report["categorical"][feat]
        cats = r["categories"]
        top = r["value_counts_top"][:5]
        print(f"  {feat}: K={len(cats)}  other_rate={r.get('other_bucket_rate', 0)}")
        print(f"    categories: {cats}")
        print(f"    top counts: {top}")

    for skip in report["skipped_categorical"]:
        print(f"  [SKIP] {skip['feature']}: {skip['reason']}")

    print("\n--- Flatten layout ---")
    for seg in report["flatten_layout"]:
        print(f"  offset {seg['offset']:3d}  size {seg['size']:2d}  {seg['kind']:12s}  {seg['feature']}")
    print("=" * 72)


def export_report(report: dict[str, Any], export_dir: Path, calibration_id: str) -> None:
    export_dir.mkdir(parents=True, exist_ok=True)

    serializable = {k: v for k, v in report.items() if not k.startswith("_")}
    serializable["calibration_id"] = calibration_id

    summary_path = export_dir / "analysis_report.json"
    summary_path.write_text(json.dumps(serializable, indent=2, ensure_ascii=False), encoding="utf-8")

    edges_preview = {
        "calibration_id": calibration_id,
        "numeric": {
            f: {
                "edges": report["numeric"][f]["edges"],
                "bin_counts": report["numeric"][f]["bin_counts"],
            }
            for f in report["numeric_features_fitted"]
        },
        "categorical": {
            f: {
                "categories": report["categorical"][f]["categories"],
                "value_counts_top": report["categorical"][f]["value_counts_top"],
            }
            for f in report["categorical_features_fitted"]
        },
    }
    (export_dir / "bin_edges_preview.json").write_text(
        json.dumps(edges_preview, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )

    readme = f"""# Bin calibration export

Generated: {report['generated_at']}
Calibration ID: {calibration_id}

Files:
  - analysis_report.json   full stats + layout
  - bin_edges_preview.json numeric edges + categorical vocab (threshold DB preview)

To persist to Azure SQL:
  python db/offline_bin_calibration.py --save-db --calibration-id {calibration_id}
"""
    (export_dir / "README.txt").write_text(readme, encoding="utf-8")
    print(f"\nExported to {export_dir.resolve()}")
    print(f"  - {summary_path.name}")
    print(f"  - bin_edges_preview.json")
    print(f"  - README.txt")


def save_to_database(
    calibration_id: str,
    feature_rows: list[tuple[str, dict[str, Any]]],
    report: dict[str, Any],
    n_bins: int,
    no_apply: bool,
    *,
    replace_all: bool,
) -> None:
    fitted_numeric = report["_fitted_numeric"]
    fitted_categorical = report["_fitted_categorical"]
    segments = report["_segments"]
    flatten_dim = report["flatten_dim"]

    if not fitted_numeric and not fitted_categorical:
        print("Nothing to save (no features passed min_samples).", file=sys.stderr)
        sys.exit(1)

    conn = connect()
    cursor = conn.cursor()
    if replace_all:
        print(f"Replace-all: clearing bin tables, calibration_id={calibration_id}")
    prepare_bin_persist(cursor, calibration_id, replace_all=replace_all)
    persist_calibration(
        cursor,
        calibration_id,
        n_bins,
        len(feature_rows),
        report["id_features"],
        report["numeric_features_fitted"],
        report["categorical_features_fitted"],
        segments,
        flatten_dim,
        fitted_numeric,
        fitted_categorical,
    )
    conn.commit()
    print(f"\nSaved calibration {calibration_id} to dbo.risk_feature_bin_calibrations / _edges")

    if not no_apply:
        edges_only = {k: v[0] for k, v in fitted_numeric.items()}
        n = apply_encodings(
            cursor,
            calibration_id,
            feature_rows,
            fitted_categorical,
            edges_only,
            n_bins,
            segments,
            flatten_dim,
            upsert_rows=not replace_all,
        )
        conn.commit()
        print(f"Wrote {n} rows to dbo.risk_feature_binned (onehot dim={flatten_dim}).")

    cursor.close()
    conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Analyze risk_features and build bin threshold calibration (offline)."
    )
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=DEFAULT_EXPORT_DIR,
        help=f"Directory for JSON report (default: {DEFAULT_EXPORT_DIR})",
    )
    parser.add_argument(
        "--calibration-id",
        default="",
        help=f"Calibration UUID (default with replace-all: {ACTIVE_CALIBRATION_ID})",
    )
    parser.add_argument(
        "--append",
        action="store_true",
        help="Keep old calibrations; only replace this calibration_id (default: full overwrite)",
    )
    parser.add_argument("--n-bins", type=int, default=DEFAULT_N_BINS)
    parser.add_argument("--min-samples", type=int, default=DEFAULT_MIN_SAMPLES)
    parser.add_argument("--max-categories", type=int, default=DEFAULT_MAX_CATEGORIES)
    parser.add_argument(
        "--from-jsonl",
        type=Path,
        default=None,
        help="Read features from JSONL instead of SQL (each line: request_id + features dict)",
    )
    parser.add_argument(
        "--save-db",
        action="store_true",
        help="Write calibration + edges (+ binned rows) to Azure SQL",
    )
    parser.add_argument(
        "--no-apply",
        action="store_true",
        help="With --save-db: only calibration/edges, skip risk_feature_binned rows",
    )
    args = parser.parse_args()

    replace_all = not args.append
    calibration_id = resolve_calibration_id(args.calibration_id, replace_all=replace_all)
    n_bins = max(2, args.n_bins)
    min_samples = max(3, args.min_samples)

    if args.from_jsonl:
        if not args.from_jsonl.is_file():
            print(f"File not found: {args.from_jsonl}", file=sys.stderr)
            sys.exit(1)
        feature_rows = load_from_jsonl(args.from_jsonl)
        print(f"Loaded {len(feature_rows)} rows from {args.from_jsonl}")
    else:
        conn = connect()
        cursor = conn.cursor()
        feature_rows = load_feature_rows(cursor)
        cursor.close()
        conn.close()
        print(f"Loaded {len(feature_rows)} rows from dbo.risk_features")

    if not feature_rows:
        print("No feature rows to analyze.", file=sys.stderr)
        sys.exit(1)

    report = analyze_features(
        feature_rows, n_bins, min_samples, args.max_categories
    )
    print_report(report)

    run_dir = args.export_dir / calibration_id
    export_report(report, run_dir, calibration_id)

    if args.save_db:
        save_to_database(
            calibration_id,
            feature_rows,
            report,
            n_bins,
            args.no_apply,
            replace_all=replace_all,
        )
    else:
        print(
            "\nDry run only (JSON export). To write SQL tables, re-run with:\n"
            f"  python db/offline_bin_calibration.py --save-db --calibration-id {calibration_id}"
        )


if __name__ == "__main__":
    main()
