#!/usr/bin/env python3
"""Rewrite `ON CONFLICT (cols)` to `ON CONFLICT (tenant_id, cols)` for INSERTs into tenant-scoped tables.
usage: rescope_conflicts.py <scoped-tables-file> <sql-file>"""
import re, sys
scoped = set(open(sys.argv[1]).read().split())
text = open(sys.argv[2]).read()
out, pos, changed = [], 0, 0
for m in re.finditer(r"ON CONFLICT \(([^)]*)\)", text, re.I):
    before = text[:m.start()]
    ins = list(re.finditer(r"INSERT\s+INTO\s+(?:public\.)?(\w+)", before, re.I))
    table = ins[-1].group(1).lower() if ins else None
    cols = [c.strip() for c in m.group(1).split(",")]
    if table in scoped and cols and cols[0].lower() != "tenant_id":
        out.append(text[pos:m.start()]); out.append(f"ON CONFLICT (tenant_id, {m.group(1).strip()})"); pos = m.end(); changed += 1
out.append(text[pos:])
open(sys.argv[2], "w").write("".join(out))
print(f"  {sys.argv[2].split('/')[-1]}: {changed} conflict targets re-scoped")
