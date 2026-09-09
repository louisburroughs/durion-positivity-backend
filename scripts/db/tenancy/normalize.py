import re, sys
from pathlib import Path

if len(sys.argv) != 4:
    sys.exit("usage: normalize.py <schema-dump.sql> <out.sql> <dir-containing-flatten.py>")
sys.path.insert(0, sys.argv[3])
from flatten import split_statements  # noqa: E402

text = Path(sys.argv[1]).read_text(encoding="utf-8")
out = []
for s in split_statements(text):
    if any(k in s for k in ("flyway_schema_history", "_tenant_key", "_tenant_idx", "ROW LEVEL SECURITY", "CREATE POLICY", "app_current_tenant()\n", "FUNCTION public.app_current_tenant")):
        continue
    if s.startswith(("SET ", "SELECT pg_catalog.set_config", "COMMENT ON EXTENSION")):
        continue
    s = s.replace(" DEFAULT public.app_current_tenant()", "")
    s = "\n".join(l for l in s.split("\n") if not re.match(r"^\s+tenant_id uuid", l))
    s = s.replace("(tenant_id, ", "(").replace("tenant_id WITH =, ", "")
    # Postgres deparses IN-lists two equivalent ways depending on how the CHECK was created
    canon = lambda m: m.group(1) + " [" + ",".join(re.findall(r"'([^']*)'", m.group(2))) + "]"
    s = re.sub(r"(ANY|ALL) \((\(ARRAY\[[^\]]*\]\)::text\[\])\)", canon, s)
    s = re.sub(r"(ANY|ALL) \((ARRAY\[[^\]]*\])\)", canon, s)
    out.append(s)
Path(sys.argv[2]).write_text("\n".join(out) + "\n", encoding="utf-8")
