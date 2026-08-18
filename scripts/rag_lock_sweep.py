#!/usr/bin/env python3
"""Gate 5 retrieval-lock sweep — live half (#1217, Wave 1 step 2 of
`pos-mcp-server/docs/gate-closeout-plan-1212-1219.md`).

Compares the on-disk content hash of every static RAG document declared under
`mcp.rag.preload.docs` against the `mcp_rag_preload_record` rows the service actually wrote when it
embedded that corpus, so drift between the shipped corpus and what is really in the database is
caught instead of being assumed away.

Companion halves of the same lock:

  * offline — `pos-mcp-server/src/test/java/com/positivity/mcp/eval/RetrievalLockTest.java` asserts
    deterministic/unique id, `rag-scope`, an explicit permission decision, a resolvable
    `source-path`, and documented chunking. It runs in the normal module surefire build.
  * live (this script) — content hash, preload status, scope/path agreement, embedded chunk
    presence, and stale rows for documents that have left the corpus.

Why the hash comparison is the load-bearing check: `StaticRagPreloadServiceImpl.preloadDocument`
SHA-256s the raw resource bytes and, when the newest LOADED row for (document_id, rag_scope) carries
the same hash, records a SKIPPED row and does not re-embed. A corpus edit that never reached the
running service therefore looks perfectly healthy from the application side — the only way to see it
is to hash the files on disk and compare, which is what this does.

Schema (read from the migrations, not guessed): `V9__rag_preload_tracking.sql` creates
`mcp_rag_preload_record (id, document_id, content_hash, source_path, status, loaded_at)`,
`V10__rag_preload_add_job_id.sql` adds `job_id`, `V15__rag_preload_scope_tracking.sql` adds
`rag_scope` (NOT NULL, backfilled to 'master') and re-indexes on
`(document_id, rag_scope, status, loaded_at DESC)`. Rows are immutable audit records — one per
preload event, never updated — so "current state" is the newest row per (document_id, rag_scope).
`RagPreloadStatus` is QUEUED | LOADED | SKIPPED | FAILED: QUEUED means an ingestion job was submitted
and has not been reconciled yet, LOADED means the job succeeded, SKIPPED means the content hash
already matched a prior LOADED row, FAILED means the job (or the reconcile) failed.

Safety model: strictly read-only. Every statement is routed through `query()`, which refuses any SQL
that is not a SELECT, and the session is opened read-only. No DDL, no writes, no re-seed — use
`scripts/rag_seed.py` for that, deliberately kept a separate tool.

Exit codes: 0 = every document locked (or `--dry-run`), 1 = drift detected, 2 = configuration /
usage error, 3 = infrastructure error (driver missing, DB unreachable).

Deps: pg8000 (pure-Python PG driver: `pip install --user pg8000`), and PyYAML if it happens to be
installed (a stdlib fallback parser handles the preload block otherwise). DB configuration is
resolved by `scripts/eval_live.py` (env, then `$ENV_FILE`/`.env`), same as `scripts/rag_diag.py` and
`scripts/rag_seed.py`.

Usage:
    # offline review of exactly what the sweep will do (Wave 0 — no DB connection is opened)
    python3 scripts/rag_lock_sweep.py --dry-run

    # Wave 1 step 2 on alpha: closes #1217
    pip install --user pg8000
    ENV_FILE=/opt/durion/alpha/.env python3 scripts/rag_lock_sweep.py
"""
import argparse
import json
import hashlib
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MCP = ROOT / "pos-mcp-server"
# The alpha overlay is the effective corpus: StaticRagPreloadServiceImpl is @Profile("alpha"), and a
# profile-specific list replaces (never merges with) the base list. RetrievalLockTest asserts the two
# files agree, so either is a valid --config, but the default is the one that actually preloads.
DEFAULT_CONFIG = MCP / "src/main/resources/application-alpha.yml"
RESOURCE_ROOT = MCP / "src/main/resources"
DEFAULT_OUT = MCP / "target/rag-lock-sweep"

PRELOAD_TABLE = "mcp_rag_preload_record"
EMBEDDING_TABLE = "mcp_document_embedding"
CLASSPATH_PREFIX = "classpath:"
# #1124: the corpus reached 39 documents. A sweep that finds fewer is itself drift — the manifest
# was trimmed rather than the database.
MIN_DOCS = 39
# Rendered in place of a manifest entry whose `id` is missing or blank.
MISSING_ID_LABEL = "<MISSING ID>"

# Terminal statuses that mean "this exact content is embedded". QUEUED is in-flight (the reconcile
# pass on the next startup resolves it) and FAILED means the ingest never landed.
EMBEDDED_STATUSES = ("LOADED", "SKIPPED")

OK = "OK"
DRIFT = "DRIFT"
PASS = "PASS"
FAIL = "FAIL"

EXIT_OK = 0
EXIT_DRIFT = 1
EXIT_USAGE = 2
EXIT_INFRA = 3


# --- manifest ------------------------------------------------------------------------------------
def normalize_scope(scope):
    """RagScope.normalize: trim + lowercase, blank -> 'master'."""
    return (scope or "").strip().lower() or "master"


def _scalar(raw):
    raw = raw.strip()
    if raw.startswith(("'", '"')) and raw.endswith(("'", '"')) and len(raw) >= 2:
        return raw[1:-1]
    return raw


def _inline_list(raw):
    raw = raw.strip()
    if not raw or raw in ("[]", "~", "null"):
        return []
    if raw.startswith("[") and raw.endswith("]"):
        raw = raw[1:-1]
    return [_scalar(part) for part in raw.split(",") if part.strip()]


def parse_preload_docs_fallback(text):
    """Stdlib parser for the `mcp.rag.preload.docs` block, used when PyYAML is unavailable.

    Deliberately narrow: it only understands the shape the repo actually uses (a `docs:` sequence of
    mappings with scalar `id` / `source-path` / `rag-scope` and an inline-list
    `required-permissions`). Anything else raises rather than being silently dropped, because a
    quietly-skipped entry would turn this sweep into a false PASS.
    """
    lines = text.splitlines()
    docs_indent = None
    start = None
    seen_preload = False
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped == "preload:":
            seen_preload = True
            continue
        if seen_preload and stripped == "docs:":
            docs_indent = len(line) - len(line.lstrip(" "))
            start = index + 1
            break
    if start is None:
        raise ValueError("mcp.rag.preload.docs block not found")

    docs, current = [], None
    for line in lines[start:]:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent <= docs_indent:
            break
        if stripped.startswith("- "):
            current = {}
            docs.append(current)
            stripped = stripped[2:].strip()
        if current is None:
            raise ValueError(f"unexpected line before the first list item: {line!r}")
        if ":" not in stripped:
            raise ValueError(f"unparsable preload line: {line!r}")
        key, _, value = stripped.partition(":")
        key = key.strip()
        if key == "required-permissions":
            current[key] = _inline_list(value)
        else:
            current[key] = _scalar(value)
    return docs


def load_manifest(config_path):
    """Returns [{id, source_path, rag_scope, required_permissions}] from the preload config."""
    text = Path(config_path).read_text(encoding="utf-8")
    parser = "stdlib"
    raw_docs = None
    try:
        import yaml  # optional; the fallback parser covers hosts without PyYAML
        parser = "PyYAML"
        loaded = yaml.safe_load(text) or {}
        raw_docs = (((loaded.get("mcp") or {}).get("rag") or {}).get("preload") or {}).get("docs")
    except ImportError:
        pass
    if raw_docs is None:
        parser = "stdlib"
        raw_docs = parse_preload_docs_fallback(text)

    manifest = []
    for entry in raw_docs:
        source_path = entry.get("source-path") or ""
        manifest.append({
            # A missing or blank id is itself drift, not a crash: normalize to "" here and let
            # sweep_document/do_dry_run report it alongside the other lock failures.
            "id": entry.get("id") or "",
            "source_path": source_path,
            "rag_scope": normalize_scope(entry.get("rag-scope")),
            "required_permissions": entry.get("required-permissions") or [],
            "relative_path": source_path[len(CLASSPATH_PREFIX):]
            if source_path.startswith(CLASSPATH_PREFIX) else source_path,
        })
    return manifest, parser


def disk_hash(relative_path):
    """SHA-256 over raw bytes — byte-for-byte what StaticRagPreloadServiceImpl.computeHash stores."""
    file = RESOURCE_ROOT / relative_path
    if not file.is_file():
        return None
    return hashlib.sha256(file.read_bytes()).hexdigest()


# --- database ------------------------------------------------------------------------------------
def query(con, sql, **params):
    """Read-only gate: this sweep must never mutate the corpus it is auditing."""
    if not sql.lstrip().upper().startswith("SELECT"):
        raise RuntimeError(f"rag_lock_sweep is read-only; refused non-SELECT statement: {sql[:60]!r}")
    return con.run(sql, **params)


def connect(cfg):
    try:
        import pg8000.native
    except ImportError:
        _print("Missing driver. Run:  pip install --user pg8000")
        raise SystemExit(EXIT_INFRA)
    if not cfg["user"] or not cfg["password"]:
        _print("Could not resolve DB user/password (env or .env: "
               "SPRING_DATASOURCE_USERNAME/PASSWORD). Set ENV_FILE=/opt/durion/alpha/.env")
        raise SystemExit(EXIT_USAGE)
    try:
        con = pg8000.native.Connection(
            user=cfg["user"], password=cfg["password"], host=cfg["host"],
            port=cfg["port"], database=cfg["database"],
        )
    except Exception as exc:  # noqa: BLE001 - any driver/network failure is an infra failure
        _print(f"Could not connect to {cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['database']}: {exc}")
        raise SystemExit(EXIT_INFRA)
    # Belt and braces on top of query(): the server rejects writes for this session outright.
    con.run("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")
    return con


def latest_records(con):
    """Newest audit row per (document_id, rag_scope) — rows are immutable, so newest == current."""
    rows = query(con, f"""
        SELECT DISTINCT ON (document_id, rag_scope)
               document_id, rag_scope, content_hash, source_path, status, job_id, loaded_at
        FROM {PRELOAD_TABLE}
        ORDER BY document_id, rag_scope, loaded_at DESC
    """)
    return {(row[0], row[1]): {
        "document_id": row[0], "rag_scope": row[1], "content_hash": row[2],
        "source_path": row[3], "status": row[4], "job_id": str(row[5]) if row[5] else None,
        "loaded_at": row[6].isoformat() if row[6] else None,
    } for row in rows}


def embedded_chunk_counts(con):
    """document_id -> (chunks, chunks_with_embedding) for the preloaded corpus only."""
    rows = query(con, f"""
        SELECT metadata->>'document_id', metadata->>'rag_scope', COUNT(*), COUNT(embedding)
        FROM {EMBEDDING_TABLE}
        WHERE metadata->>'source_path' LIKE 'classpath:rag/%'
        GROUP BY 1, 2
    """)
    return {(row[0], normalize_scope(row[1])): {"chunks": row[2], "embedded": row[3]} for row in rows}


# --- sweep ---------------------------------------------------------------------------------------
def sweep_document(doc, records, chunk_counts, check_embeddings):
    """Locks one manifest entry against the DB. Returns (verdict, [reason, ...], detail)."""
    reasons = []
    if not doc["id"]:
        reasons.append("INVALID_ID: manifest entry declares no `id`; it can never match a DB row")
    expected_hash = disk_hash(doc["relative_path"])
    key = (doc["id"], doc["rag_scope"])
    record = records.get(key)
    detail = {
        "document_id": doc["id"],
        "rag_scope": doc["rag_scope"],
        "source_path": doc["source_path"],
        "required_permissions": doc["required_permissions"],
        "disk_hash": expected_hash,
        "db_hash": record["content_hash"] if record else None,
        "db_status": record["status"] if record else None,
        "db_loaded_at": record["loaded_at"] if record else None,
        "chunks": None,
    }

    if expected_hash is None:
        # The offline RetrievalLockTest already fails the build on this; repeated here so a live-only
        # run against a mismatched checkout still reports honestly.
        reasons.append("SOURCE_MISSING: no file at src/main/resources/" + doc["relative_path"])

    if record is None:
        scopes = sorted({k[1] for k in records if k[0] == doc["id"]})
        if scopes:
            reasons.append(f"SCOPE_DRIFT: no row for scope '{doc['rag_scope']}'; DB has {scopes}")
        else:
            reasons.append("NOT_PRELOADED: no mcp_rag_preload_record row for this document")
    else:
        if record["status"] not in EMBEDDED_STATUSES:
            reasons.append(f"STATUS_{record['status']}: newest preload row is not LOADED/SKIPPED")
        if expected_hash and record["content_hash"] != expected_hash:
            reasons.append(
                f"HASH_DRIFT: disk {expected_hash[:12]}... != db {str(record['content_hash'])[:12]}...")
        if record["source_path"] != doc["source_path"]:
            reasons.append(
                f"PATH_DRIFT: db source_path '{record['source_path']}' != manifest '{doc['source_path']}'")

    if check_embeddings:
        counts = chunk_counts.get(key)
        detail["chunks"] = counts["chunks"] if counts else 0
        if not counts or counts["chunks"] == 0:
            reasons.append("NO_CHUNKS: nothing in mcp_document_embedding for this document/scope")
        elif counts["embedded"] < counts["chunks"]:
            reasons.append(
                f"NULL_EMBEDDINGS: {counts['chunks'] - counts['embedded']}/{counts['chunks']} chunks "
                "have a NULL embedding vector")

    detail["reasons"] = reasons
    detail["verdict"] = OK if not reasons else DRIFT
    return detail


def find_orphans(manifest, records, chunk_counts, check_embeddings):
    """Rows for documents that have left the manifest — stale content still retrievable on alpha."""
    declared = {(doc["id"], doc["rag_scope"]) for doc in manifest}
    orphans = []
    for key, record in sorted(records.items()):
        if key not in declared:
            orphans.append({
                "document_id": key[0], "rag_scope": key[1], "status": record["status"],
                "loaded_at": record["loaded_at"], "source": PRELOAD_TABLE,
                "chunks": chunk_counts.get(key, {}).get("chunks", 0) if check_embeddings else None,
            })
    if check_embeddings:
        for key in sorted(chunk_counts):
            if key not in declared and key not in records:
                orphans.append({
                    "document_id": key[0], "rag_scope": key[1], "status": "n/a",
                    "loaded_at": None, "source": EMBEDDING_TABLE,
                    "chunks": chunk_counts[key]["chunks"],
                })
    return orphans


# --- reporting -----------------------------------------------------------------------------------
def _print(message):
    print(message)
    return True


def print_table(results):
    width = max((len(r["document_id"]) for r in results), default=20)
    print(f"{'document_id':<{width}}  {'scope':<12} {'status':<8} {'chunks':>6}  verdict")
    print(f"{'-' * width}  {'-' * 12} {'-' * 8} {'-' * 6}  -------")
    for result in results:
        chunks = "-" if result["chunks"] is None else str(result["chunks"])
        print(f"{result['document_id']:<{width}}  {result['rag_scope']:<12} "
              f"{str(result['db_status'] or '-'):<8} {chunks:>6}  {result['verdict']}")
        for reason in result["reasons"]:
            print(f"{'':<{width}}    ! {reason}")


def build_markdown(args, results, orphans, started, cfg, parser):
    locked = [r for r in results if r["verdict"] == OK]
    drifted = [r for r in results if r["verdict"] != OK]
    # Must mirror the exit-code rule in main() exactly, or the evidence block can say HOLD while the
    # script exits 0 (or the reverse): honour --min-docs and --allow-orphans rather than the floor.
    orphans_fail = bool(orphans) and not args.allow_orphans
    passed = not drifted and not orphans_fail and len(results) >= args.min_docs
    decision = "Pass" if passed else "HOLD"
    permissioned = [r for r in results if r["required_permissions"]]
    public = [r for r in results if not r["required_permissions"]]
    chunk_issues = [r for r in results
                    if any(reason.startswith(("NO_CHUNKS", "NULL_EMBEDDINGS")) for reason in r["reasons"])]

    def ev(condition, text):
        return f"- [{'x' if condition else ' '}] {text}"

    out = [
        "<!-- generated by scripts/rag_lock_sweep.py (#1217) — paste into "
        "pos-mcp-server/docs/implementation_checklist.md, Gate 5 -->",
        "",
        f"### Gate 5 — Retrieval-lock sweep ({args.issue})",
        "",
        f"- Command: `scripts/rag_lock_sweep.py --config {Path(args.config).name}` · "
        f"run `{started.isoformat(timespec='seconds')}` · manifest parser `{parser}`",
        f"- Target: `{cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['database']}` "
        f"(read-only session) · tables `{PRELOAD_TABLE}`, `{EMBEDDING_TABLE}`",
        f"- Result: **{'PASS' if passed else 'FAIL'}** — {len(locked)}/{len(results)} documents "
        f"locked, {len(drifted)} drifted, {len(orphans)} orphaned",
        "",
        "#### Retrieval lock (every doc)",
        "",
        ev(len(results) >= args.min_docs,
           f"Corpus size — _ev: {len(results)} documents declared in "
           f"`mcp.rag.preload.docs` (floor {args.min_docs})_"),
        ev(True,
           "Deterministic ID, `rag-scope`, permission metadata, `source-path`, documented chunking "
           "— _ev: asserted offline by `RetrievalLockTest` in the pos-mcp-server surefire build_"),
        ev(not drifted,
           f"Content hash — _ev: SHA-256 of each `src/main/resources/rag/*.md` matches the newest "
           f"LOADED/SKIPPED `{PRELOAD_TABLE}` row; {len(drifted)} mismatch(es)_"),
        ev(not orphans,
           f"No stale corpus — _ev: {len(orphans)} document(s) present in the database but absent "
           "from the manifest_"),
        ev(args.check_embeddings and not chunk_issues,
           f"Chunks embedded — _ev: {sum(r['chunks'] or 0 for r in results)} chunk rows across "
           f"{len(results)} documents in `{EMBEDDING_TABLE}`, {len(chunk_issues)} document(s) with "
           "missing chunks or NULL vectors_"
           if args.check_embeddings else "Chunks embedded — _ev: skipped (--no-embedding-check)_"),
        "",
        "#### Permission coverage",
        "",
        f"- {len(permissioned)}/{len(results)} documents carry `required-permissions`; "
        f"{len(public)} are public by declaration "
        f"({', '.join('`' + r['document_id'] + '`' for r in public) or 'none'}). "
        "Empty means unrestricted in `PermissionAwareMetadataFilter.isVisible` — the pinned public "
        "set is enforced by `RetrievalLockTest`.",
        "",
        "#### Metrics",
        "",
        "| Metric | Value | Pass/Fail |",
        "| ------ | ----: | --------- |",
        f"| documents declared | {len(results)} | {PASS if len(results) >= args.min_docs else FAIL} |",
        f"| documents hash-locked | {len(locked)} | {PASS if not drifted else FAIL} |",
        f"| documents drifted | {len(drifted)} | {PASS if not drifted else FAIL} |",
        f"| orphaned documents | {len(orphans)} | {PASS if not orphans_fail else FAIL} |"
        + (" <!-- waived by --allow-orphans -->" if orphans and args.allow_orphans else ""),
        f"| embedded chunks | {sum(r['chunks'] or 0 for r in results)} | "
        f"{PASS if not args.check_embeddings or all(r['chunks'] for r in results) else FAIL} |",
        "",
    ]
    if drifted or orphans:
        out += ["#### Drift detail", ""]
        for result in drifted:
            out.append(f"- `{result['document_id']}` (scope `{result['rag_scope']}`): "
                       + "; ".join(result["reasons"]))
        for orphan in orphans:
            out.append(f"- `{orphan['document_id']}` (scope `{orphan['rag_scope']}`): ORPHAN in "
                       f"`{orphan['source']}`, status {orphan['status']}")
        out.append("")
    out += [
        "#### Sign-off record",
        "",
        "```",
        "Gate:           5",
        f"Date:           {started.date().isoformat()}",
        "Executed by:    scripts/rag_lock_sweep.py (read-only)",
        f"Scope complete: {len(locked)}/{len(results)} documents locked",
        "Cross-locks:    Retrieval lock + Permission lock",
        f"Decision:       {decision}",
        f"Exceptions:     {len(drifted)} drifted, {len(orphans)} orphaned",
        "Rollback:       n/a (verification only)",
        "Next-gate appr: <approver / date / conditions>",
        "```",
        "",
    ]
    return "\n".join(out)


def do_dry_run(args, manifest, parser, cfg):
    print("rag_lock_sweep.py — DRY RUN (no database connection is opened)")
    print(f"  manifest      {args.config}")
    print(f"  parser        {parser}")
    print(f"  resource root {RESOURCE_ROOT}")
    print(f"  db target     {cfg['user'] or '<unresolved>'}@{cfg['host']}:{cfg['port']}/"
          f"{cfg['database']} (read-only session, SELECT statements only)")
    print(f"  outputs       {args.markdown_out}\n                {args.json_out}")
    print(f"\nplanned SELECTs against {PRELOAD_TABLE}"
          + (f" and {EMBEDDING_TABLE}" if args.check_embeddings else "")
          + ":")
    print(f"  1. newest row per (document_id, rag_scope) from {PRELOAD_TABLE}")
    if args.check_embeddings:
        print(f"  2. chunk + non-null-embedding counts per (document_id, rag_scope) from "
              f"{EMBEDDING_TABLE} where source_path LIKE 'classpath:rag/%'")
    print(f"\nper-document checks ({len(manifest)} documents, floor {args.min_docs}):")
    print("  * source file present under src/main/resources")
    print(f"  * newest preload row exists for the declared scope and its status is one of "
          f"{list(EMBEDDED_STATUSES)}")
    print("  * SHA-256 of the on-disk bytes == that row's content_hash")
    print("  * that row's source_path == the manifest source_path")
    if args.check_embeddings:
        print("  * at least one chunk row, with no NULL embedding vectors")
    print("  plus a corpus-wide orphan check for DB documents absent from the manifest")
    print()
    labels = [doc["id"] or MISSING_ID_LABEL for doc in manifest]
    width = max((len(label) for label in labels), default=20)
    for doc, label in zip(manifest, labels):
        computed = disk_hash(doc["relative_path"])
        perms = ",".join(doc["required_permissions"]) or "<public>"
        print(f"  {label:<{width}}  scope={doc['rag_scope']:<12} "
              f"sha256={computed[:16] + '...' if computed else 'FILE MISSING':<19} perms={perms}")
    print(f"\n{len(manifest)} documents planned. Exit 0 (dry run).")


# --- CLI -----------------------------------------------------------------------------------------
def build_parser():
    parser = argparse.ArgumentParser(
        prog="rag_lock_sweep.py",
        description="Gate 5 retrieval-lock sweep: on-disk RAG corpus vs the mcp_rag_preload_record "
                    "rows in the database (#1217). Read-only.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Exit codes: 0 locked, 1 drift, 2 usage, 3 infrastructure.")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG),
                        help=f"preload config to read the manifest from (default {DEFAULT_CONFIG.name})")
    parser.add_argument("--issue", default="#1217", help="issue reference used in the evidence block")
    parser.add_argument("--min-docs", type=int, default=MIN_DOCS,
                        help=f"fail if fewer documents are declared (default {MIN_DOCS})")
    parser.add_argument("--no-embedding-check", dest="check_embeddings", action="store_false",
                        help=f"skip the {EMBEDDING_TABLE} chunk-presence cross-check")
    parser.add_argument("--allow-orphans", action="store_true",
                        help="report documents that are in the DB but not the manifest without failing")
    parser.add_argument("--out", default=str(DEFAULT_OUT),
                        help=f"output directory (default {DEFAULT_OUT})")
    parser.add_argument("--markdown-out", default=None, help="override the markdown evidence path")
    parser.add_argument("--json-out", default=None, help="override the JSON result path")
    parser.add_argument("--quiet", action="store_true",
                        help="write the artefacts but do not echo the markdown block")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the plan and exit 0 without opening a database connection")
    return parser


def db_config():
    """Same resolution order as eval_live.py / rag_diag.py / rag_seed.py (env, then .env)."""
    try:
        import eval_live as E
    except Exception:  # noqa: BLE001 - keep --dry-run/--help usable on a bare checkout
        return {"host": "localhost", "port": 5432, "database": "pos_mcp", "user": None, "password": None}
    return {"host": E.DB_HOST, "port": E.DB_PORT, "database": E.DB_NAME,
            "user": E.DB_USER, "password": E.DB_PASS}


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.markdown_out = args.markdown_out or str(Path(args.out) / "rag-lock-sweep.md")
    args.json_out = args.json_out or str(Path(args.out) / "rag-lock-sweep.json")
    started = datetime.now(timezone.utc)

    config_path = Path(args.config)
    if not config_path.is_file():
        _print(f"--config not found: {config_path}")
        return EXIT_USAGE
    try:
        manifest, parser = load_manifest(config_path)
    except Exception as exc:  # noqa: BLE001 - a manifest we cannot parse is a usage error
        _print(f"Could not read the preload manifest from {config_path}: {exc}")
        return EXIT_USAGE
    if not manifest:
        _print(f"No mcp.rag.preload.docs entries found in {config_path}")
        return EXIT_USAGE

    cfg = db_config()
    if args.dry_run:
        do_dry_run(args, manifest, parser, cfg)
        return EXIT_OK

    con = connect(cfg)
    try:
        print(f"DB={cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['database']}  "
              f"manifest={config_path.name} ({len(manifest)} docs, parser {parser})\n")
        records = latest_records(con)
        chunk_counts = embedded_chunk_counts(con) if args.check_embeddings else {}
    finally:
        con.close()

    results = [sweep_document(doc, records, chunk_counts, args.check_embeddings) for doc in manifest]
    orphans = find_orphans(manifest, records, chunk_counts, args.check_embeddings)
    print_table(results)

    drifted = [r for r in results if r["verdict"] != OK]
    undersized = len(manifest) < args.min_docs
    if orphans:
        print(f"\n{len(orphans)} orphaned document(s) in the database:")
        for orphan in orphans:
            print(f"  ! {orphan['document_id']} (scope {orphan['rag_scope']}, "
                  f"{orphan['source']}, status {orphan['status']})")
    if undersized:
        print(f"\n! only {len(manifest)} documents declared, expected at least {args.min_docs}")

    markdown = build_markdown(args, results, orphans, started, cfg, parser)
    payload = {
        "issue": args.issue,
        "startedAt": started.isoformat(timespec="seconds"),
        "config": str(config_path),
        "manifestParser": parser,
        "database": {"host": cfg["host"], "port": cfg["port"], "name": cfg["database"],
                     "readOnly": True},
        "documents": results,
        "orphans": orphans,
        "summary": {
            "declared": len(manifest),
            "minDocs": args.min_docs,
            "locked": len(results) - len(drifted),
            "drifted": len(drifted),
            "orphaned": len(orphans),
            "chunks": sum(r["chunks"] or 0 for r in results),
        },
    }
    for path, body in ((args.markdown_out, markdown), (args.json_out, json.dumps(payload, indent=2))):
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding="utf-8")
    if not args.quiet:
        print("\n" + markdown)
    print(f"wrote {args.markdown_out}\nwrote {args.json_out}")

    failed = bool(drifted) or undersized or (bool(orphans) and not args.allow_orphans)
    if failed:
        print(f"\nRETRIEVAL LOCK FAIL: {len(drifted)} drifted, {len(orphans)} orphaned, "
              f"{len(manifest)} declared")
        return EXIT_DRIFT
    print(f"\nRETRIEVAL LOCK OK: {len(results)} documents hash-locked against {PRELOAD_TABLE}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
