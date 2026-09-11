#!/usr/bin/env python3
"""
flatten.py - build one Flyway baseline per module from a pg_dump --schema-only dump, folding the
ADR-0062 tenancy schema into it.

For every tenant-scoped table (everything not listed in the module's global-table whitelist):
  * tenant_id uuid NOT NULL DEFAULT public.app_current_tenant()  (added if absent)
  * ENABLE + FORCE ROW LEVEL SECURITY and a `tenant_isolation` policy
  * UNIQUE constraints, unique indexes and EXCLUDE constraints re-scoped to lead with tenant_id
  * UNIQUE (tenant_id, <pk>) so composite foreign keys can target the row within the tenant
  * FOREIGN KEY (tenant_id, x) REFERENCES y (tenant_id, id) for every scoped -> scoped reference
  * an index on tenant_id when no existing index already leads with it

Global tables are emitted exactly as dumped. flyway_schema_history is dropped.

usage: flatten.py <module> <schema.dump.sql> <out-dir>
"""
import hashlib
import re
import sys
from pathlib import Path

DEFAULT_TENANT_ID = "01900000-0000-7000-8000-000000000001"

# Tables that are global (no tenant_id, no policy) per ADR-0062 section 5, with the reason recorded
# in the module's tenancy-global-tables.txt. Everything else is tenant-scoped.
COMMON_GLOBAL = {
    "event_outbox": "transactional outbox; the unbound poller publishes every tenant's rows (tenant_id carried as data)",
    "kafka_event_outbox": "transactional outbox; the unbound poller publishes every tenant's rows (tenant_id carried as data)",
    "supplier_event_outbox": "transactional outbox; the unbound poller publishes every tenant's rows (tenant_id carried as data)",
    "processed_events": "consumer idempotency ledger keyed by eventId; checked before the tenant is bound",
}
MODULE_GLOBAL = {
    "pos-bulk-loader": {
        "batch_job_execution": "Spring Batch metadata",
        "batch_job_execution_context": "Spring Batch metadata",
        "batch_job_execution_params": "Spring Batch metadata",
        "batch_job_instance": "Spring Batch metadata",
        "batch_step_execution": "Spring Batch metadata",
        "batch_step_execution_context": "Spring Batch metadata",
    },
    "pos-event-receiver": {
        "event_type": "platform event-type registry, registered by every service at startup",
        "preregistered_event": "platform event-type registry, registered by every service at startup",
    },
    "pos-mcp-server": {
        "llm_api_config": "platform LLM provider configuration",
        "llm_api_config_headers": "platform LLM provider configuration",
        "mcp_document_embedding": "RAG corpus describing the platform itself, not tenant data",
        "mcp_document_ingestion_job": "RAG corpus ingestion bookkeeping",
        "mcp_rag_preload_record": "RAG corpus preload bookkeeping",
        "mcp_screen_registry": "platform screen registry (code-first)",
        "mcp_simple_chat_rule": "platform chat rules (code-first)",
        "mcp_tool": "platform tool catalog (code-first)",
        "mcp_tool_permission": "platform tool catalog (code-first)",
        "mcp_tool_prerequisite": "platform tool catalog (code-first)",
        "mcp_tool_workflow": "platform tool catalog (code-first)",
        "mcp_workflow_state": "platform tool catalog (code-first)",
        "system_prompt": "platform prompt catalog",
    },
    "pos-security-service": {
        "permissions": "permission catalog is global and code-first (ADR-0062 section 6, ADR-0040)",
    },
}
WHOLE_MODULE_GLOBAL = {
    "pos-vehicle-reference-nhtsa": "vehicle reference data (ADR-0062 section 5)",
    "pos-vehicle-reference-carapi": "vehicle reference data (ADR-0062 section 5)",
    "pos-vehicle-fitment": "fitment reference data (ADR-0062 section 5)",
}

TENANT_COL_LINE = "    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,"

# Scoped tables whose PRIMARY KEY must lead with tenant_id instead of keeping the source key and
# gaining a side _tenant_key. Leaving the key global is safe only for a UUID v7 surrogate, which no
# two tenants can mint alike. These tables are keyed on a *business* key -- derived from the row's
# own content, or from data a caller supplies -- and primary and unique constraints are enforced
# across every row whatever row-level security hides, so a global key lets one tenant's insert
# collide with a row it cannot see. See docs/TENANCY_SCHEMA.md, "Business-key primary keys".
TENANT_LED_PK = {
    # SHA-256 of the image bytes: two tenants storing the same picture derive it identically.
    "image_content",
}


def ident(name: str) -> str:
    if len(name) <= 63:
        return name
    h = hashlib.sha1(name.encode()).hexdigest()[:8]
    return name[:54] + "_" + h


def split_statements(text: str):
    """Yield SQL statements from a pg_dump, dollar-quote aware, dropping comments and psql meta."""
    stmt, in_dollar = [], False
    for line in text.splitlines():
        if not stmt:
            s = line.strip()
            if not s or s.startswith("--") or s.startswith("\\"):
                continue
        stmt.append(line)
        if line.count("$$") % 2 == 1:
            in_dollar = not in_dollar
        if not in_dollar and line.rstrip().endswith(";"):
            yield "\n".join(stmt)
            stmt = []
    if stmt:
        yield "\n".join(stmt)


RE_TABLE = re.compile(r"^CREATE TABLE public\.(\w+) \($", re.M)
RE_CONSTRAINT = re.compile(
    r"^ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) (PRIMARY KEY|UNIQUE|FOREIGN KEY|EXCLUDE|CHECK) (.*);$",
    re.S,
)
RE_INDEX = re.compile(r"^CREATE (UNIQUE )?INDEX (\w+) ON public\.(\w+) USING (\w+) \((.*)\)(.*);$", re.S)
RE_UNIQUE_SPEC = re.compile(r"^(NULLS NOT DISTINCT )?\((.*)\)$", re.S)
RE_FK = re.compile(r"^\(([^)]+)\) REFERENCES public\.(\w+)\(([^)]+)\)(.*)$", re.S)


def cols_of(spec: str):
    return tuple(c.strip() for c in spec.split(","))


def main(module: str, dump: Path, out_dir: Path):
    domain = module.removeprefix("pos-").replace("-", "_")
    text = dump.read_text()
    stmts = list(split_statements(text))

    globals_ = dict(COMMON_GLOBAL)
    globals_.update(MODULE_GLOBAL.get(module, {}))
    whole = WHOLE_MODULE_GLOBAL.get(module)

    # pass 1: inventory
    tables, has_tenant, pk, uniques, uidx = {}, set(), {}, {}, {}
    for s in stmts:
        m = RE_TABLE.match(s)
        if m:
            t = m.group(1)
            tables[t] = s
            if re.search(r"^\s+tenant_id uuid", s, re.M):
                has_tenant.add(t)
            continue
        m = RE_CONSTRAINT.match(s)
        if m:
            t, kind, spec = m.group(1), m.group(3), m.group(4)
            if kind == "PRIMARY KEY":
                pk[t] = cols_of(spec.strip()[1:-1])
            elif kind == "UNIQUE":
                uniques.setdefault(t, set()).add(cols_of(RE_UNIQUE_SPEC.match(spec.strip()).group(2)))
            continue
        m = RE_INDEX.match(s)
        if m and m.group(1) and not m.group(6).strip():
            uidx.setdefault(m.group(3), set()).add(cols_of(m.group(5)))
    tables.pop("flyway_schema_history", None)
    if whole:
        globals_ = {t: whole for t in tables}
    scoped = {t for t in tables if t not in globals_}
    unknown_globals = [t for t in globals_ if t not in tables and t not in COMMON_GLOBAL]
    if unknown_globals:
        sys.exit(f"{module}: whitelist names tables that do not exist: {unknown_globals}")

    def is_scoped(t):
        return t in scoped

    out, notes = [], []
    tenant_keys_added = set()  # (table, cols) unique sets that exist after rescoping

    def tenant_key_stmt(t, cols):
        name = ident(f"{t}_tenant_key" if cols == pk.get(t) else f"{t}_{'_'.join(cols)}_tenant_key")
        tenant_keys_added.add((t, cols))
        return f"ALTER TABLE ONLY public.{t}\n    ADD CONSTRAINT {name} UNIQUE (tenant_id, {', '.join(cols)});"

    fk_fixups = []  # statements to emit before FK section if a target lacks a (tenant_id, cols) unique
    for s in stmts:
        if "flyway_schema_history" in s:
            continue
        if s.startswith("SET ") or s.startswith("SELECT pg_catalog.set_config") or s.startswith("COMMENT ON EXTENSION"):
            continue
        m = RE_TABLE.match(s)
        if m:
            t = m.group(1)
            if is_scoped(t) and t not in has_tenant:
                lines = s.split("\n")
                lines.insert(1, TENANT_COL_LINE)
                s = "\n".join(lines)
            out.append(s)
            continue
        m = RE_CONSTRAINT.match(s)
        if m:
            t, name, kind, spec = m.groups()
            if not is_scoped(t):
                out.append(s)
                continue
            if kind == "PRIMARY KEY":
                if t in TENANT_LED_PK and "tenant_id" not in pk[t]:
                    # Rewrite the key itself rather than bolting a side unique beside a global one.
                    # No _tenant_key is emitted: this key already covers (tenant_id, <pk cols>), so
                    # the side constraint would be a second identical index, and FKs can target the
                    # primary key directly.
                    cols = ", ".join(pk[t])
                    out.append(
                        f"ALTER TABLE ONLY public.{t}\n"
                        f"    ADD CONSTRAINT {ident(f'{t}_pkey')} PRIMARY KEY (tenant_id, {cols});"
                    )
                    tenant_keys_added.add((t, pk[t]))
                    notes.append(f"{t}: primary key led with tenant_id (business key, TENANT_LED_PK)")
                    continue
                out.append(s)
                if "tenant_id" not in pk[t]:
                    out.append(tenant_key_stmt(t, pk[t]))
                continue
            if kind == "UNIQUE":
                um = RE_UNIQUE_SPEC.match(spec.strip())
                nulls, cols = um.group(1) or "", cols_of(um.group(2))
                if cols[0] != "tenant_id":
                    spec = f"{nulls}(tenant_id, {', '.join(cols)})"
                    tenant_keys_added.add((t, cols))
                out.append(f"ALTER TABLE ONLY public.{t}\n    ADD CONSTRAINT {name} {kind} {spec};")
                continue
            if kind == "EXCLUDE":
                if "tenant_id WITH =" not in spec:
                    spec = re.sub(r"\((.*)\)$", r"(tenant_id WITH =, \1)", spec.strip(), count=1)
                    spec = re.sub(r"^(USING \w+) \(", r"\1 (", spec)
                    # pg_dump form: EXCLUDE USING gist (a WITH =, ...)
                    spec = spec.replace("USING gist ((tenant_id WITH =, ", "USING gist (tenant_id WITH =, ")
                out.append(f"ALTER TABLE ONLY public.{t}\n    ADD CONSTRAINT {name} {kind} {spec};")
                continue
            if kind == "FOREIGN KEY":
                fm = RE_FK.match(spec.strip())
                assert fm, s
                lcols, y, rcols, rest = cols_of(fm.group(1)), fm.group(2), cols_of(fm.group(3)), fm.group(4)
                if is_scoped(y) and lcols[0] != "tenant_id" and "tenant_id" not in pk.get(y, ()):
                    if (y, rcols) not in tenant_keys_added and rcols != pk.get(y):
                        fk_fixups.append(tenant_key_stmt(y, rcols))
                        notes.append(f"added UNIQUE (tenant_id, {', '.join(rcols)}) on {y} as a composite FK target for {t}.{name}")
                    spec = f"(tenant_id, {', '.join(lcols)}) REFERENCES public.{y}(tenant_id, {', '.join(rcols)}){rest}"
                out.append(f"ALTER TABLE ONLY public.{t}\n    ADD CONSTRAINT {name} {kind} {spec};")
                continue
            out.append(s)
            continue
        m = RE_INDEX.match(s)
        if m:
            uniq, name, t, method, cols, rest = m.groups()
            if is_scoped(t) and uniq and not cols.startswith("tenant_id"):
                cols = f"tenant_id, {cols}"
            out.append(f"CREATE {uniq or ''}INDEX {name} ON public.{t} USING {method} ({cols}){rest};")
            continue
        out.append(s)

    # composite-FK targets that needed an extra unique set: emit before the first FK statement
    if fk_fixups:
        first_fk = next(i for i, x in enumerate(out) if "FOREIGN KEY" in x)
        out[first_fk:first_fk] = fk_fixups

    # tenant defaults for pre-existing tenant_id columns, tenant indexes, RLS
    tail = []
    for t in sorted(scoped):
        if t in has_tenant:
            tail.append(f"ALTER TABLE public.{t} ALTER COLUMN tenant_id SET DEFAULT public.app_current_tenant();")
            tail.append(f"ALTER TABLE public.{t} ALTER COLUMN tenant_id SET NOT NULL;")
    for t in sorted(scoped):
        leading = any(
            re.match(rf"^CREATE (UNIQUE )?INDEX \w+ ON public\.{t} USING \w+ \(tenant_id[,)]", x) for x in out
        )
        if not leading:
            tail.append(f"CREATE INDEX {ident(t + '_tenant_idx')} ON public.{t} USING btree (tenant_id);")
    for t in sorted(scoped):
        tail.append(
            f"ALTER TABLE public.{t} ENABLE ROW LEVEL SECURITY;\n"
            f"ALTER TABLE public.{t} FORCE ROW LEVEL SECURITY;\n"
            f"CREATE POLICY tenant_isolation ON public.{t}\n"
            f"    USING (tenant_id = public.app_current_tenant())\n"
            f"    WITH CHECK (tenant_id = public.app_current_tenant());"
        )

    header = f"""-- {module}: Flyway baseline (flattened 2026-09-09 from the previous migration history) with the
-- ADR-0062 tenancy schema folded in. Generated by scripts/db/tenancy/flatten.py from a pg_dump of a
-- database migrated with the retired scripts; the tenancy additions are mechanical and are listed in
-- docs/TENANCY_SCHEMA.md. Global (unscoped) tables: src/main/resources/db/tenancy-global-tables.txt.
--
-- Tenant context: app_current_tenant() reads the session setting app.current_tenant, which the
-- TenantAwareDataSource (pos-tenancy-common, plan WS1) binds per checkout. Until then the owner role
-- carries a transitional default (postgres/init-tenancy.sh) and tests set it per connection.
-- With nothing bound: NULL -> every scoped table reads as empty and refuses inserts (fail closed).

-- Functions are emitted before the tables they reference (pg_dump order); do not validate bodies.
SET check_function_bodies = false;

CREATE OR REPLACE FUNCTION public.app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid $$;
"""
    body = "\n\n".join(out + tail) + "\n"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"V1__baseline_{domain}.sql").write_text(header + "\n" + body)

    wl = [f"# {module}: tables with no tenant_id and no row-level-security policy (ADR-0062 section 5).",
          "# Format: <table>  # <reason>. Every other table in this module is tenant-scoped.",
          "# TenancySchemaConformanceIT (plan WS1) reads this file."]
    for t in sorted(globals_):
        if t in tables:
            wl.append(f"{t}  # {globals_[t]}")
    (out_dir / "tenancy-global-tables.txt").write_text("\n".join(wl) + "\n")

    (out_dir / ".scoped-tables").write_text("\n".join(sorted(scoped)) + "\n")
    print(f"{module}: {len(tables)} tables, {len(scoped)} scoped, {len(tables) - len(scoped)} global"
          + (f"; {len(has_tenant)} pre-existing tenant_id" if has_tenant else ""))
    for n in notes:
        print("   note:", n)


if __name__ == "__main__":
    main(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]))
