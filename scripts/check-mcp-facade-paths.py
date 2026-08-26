#!/usr/bin/env python3
"""Guards against pos-mcp-server facade tools pointing at endpoints that do not exist.

Every @Tool in pos-mcp-server/.../orchestration/tools/*FacadeTool.java calls a
downstream service through a URI template configured in application.yml, e.g.

    product-uri-template: ${POS_CATALOG_PRODUCT_URI_TEMPLATE:/catalog/v1/catalog/products/{productId}}

Those calls go through pos-api-gateway, whose routes apply StripPrefix=1 -- the
leading /{domain} segment is removed and the rest is forwarded verbatim. So the
template above reaches CATALOG as /v1/catalog/products/{productId}. pos-catalog
serves /v1/products/{productId}; the call 404s.

Nothing catches this today. Each facade tool HAS a unit test, but the tests assert
the tool calls the path the tool is CONFIGURED with:

    .expect(requestTo(BASE_URL + "/catalog/v1/catalog/products/PROD-001"))

against a MockRestServiceServer. The test encodes the same assumption as the code,
so it confirms the mistake rather than catching it -- the mock answers whatever
path it is handed. A tool can be wrong for its entire life and stay green.

This script compares each configured template against the paths every module
actually publishes in its committed openapi.yaml, which is the same source of
truth scripts/audit-rbac.py uses. Path parameters are normalised ({productId}
and {id} both become {}), and query strings are ignored -- only the path is
routed.

Usage (from the repo root; standard library plus PyYAML, as CI already installs):

    python3 scripts/check-mcp-facade-paths.py            # report + exit 1 on any miss
    python3 scripts/check-mcp-facade-paths.py --baseline scripts/mcp-facade-baseline.json

A baseline records templates whose breakage is known and tracked, so the check
can gate NEW breakage before the existing backlog is fixed -- same convention as
scripts/rbac-audit-baseline.json. The baseline is a defect inventory, not an
approval: entries are meant to disappear.
"""
import json, pathlib, re, sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: pip install --only-binary=:all: 'pyyaml==6.0.2'")

ROOT = pathlib.Path(".")
CONFIG = ROOT / "pos-mcp-server/src/main/resources/application.yml"


def configured_templates(text):
    """Yield (property, default) for each *uri-template: ${VAR:default} line.

    The default may itself contain {placeholders}, so the closing brace is found
    by depth counting rather than a non-greedy regex (which stops at the first }).
    """
    for line in text.splitlines():
        m = re.match(r"\s+([a-z0-9-]*uri-template):\s*\$\{[A-Z0-9_]+:", line)
        if not m:
            continue
        i = line.index(":", line.index("${")) + 1
        depth, out = 1, []
        while i < len(line):
            c = line[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            out.append(c)
            i += 1
        yield m.group(1), "".join(out).strip()


def normalise(path):
    """/v1/products/{productId}?q=x -> /v1/products/{} — params and query are not routed."""
    return re.sub(r"\{[^}]+\}", "{}", path.split("?")[0]).rstrip("/") or "/"


def module_paths():
    out = {}
    for spec in sorted(ROOT.glob("pos-*/openapi.yaml")):
        try:
            doc = yaml.safe_load(spec.read_text()) or {}
        except yaml.YAMLError as exc:
            print(f"warning: {spec} did not parse ({exc}); skipped", file=sys.stderr)
            continue
        out[spec.parent.name] = {normalise(p) for p in (doc.get("paths") or {})}
    return out


def main():
    baseline_path = None
    if "--baseline" in sys.argv:
        baseline_path = sys.argv[sys.argv.index("--baseline") + 1]
    baseline = {}
    if baseline_path and pathlib.Path(baseline_path).exists():
        baseline = json.load(open(baseline_path)).get("unresolved", {})

    published = module_paths()
    resolved, unresolved = [], []
    for prop, template in configured_templates(CONFIG.read_text()):
        if not template.startswith("/"):
            continue  # not a gateway-routed path
        downstream = normalise("/" + "/".join(template.strip("/").split("/")[1:]))
        serving = sorted(m for m, paths in published.items() if downstream in paths)
        (resolved if serving else unresolved).append((prop, template, downstream, serving))

    for prop, template, downstream, serving in resolved:
        print(f"  ok   {template}  ->  {serving[0]}")

    new = [u for u in unresolved if u[1] not in baseline]
    known = [u for u in unresolved if u[1] in baseline]

    if known:
        print(f"\n-- {len(known)} known-broken (baselined, tracked for fixing) --")
        for _, template, downstream, _ in known:
            print(f"  known  {template}  ->  {downstream}")
    if new:
        print(f"\nFAIL: {len(new)} facade template(s) reach no published endpoint:")
        for prop, template, downstream, _ in new:
            print(f"  {prop}")
            print(f"      configured : {template}")
            print(f"      downstream : {downstream}   (after gateway StripPrefix=1)")
            print(f"      published  : nothing in any pos-*/openapi.yaml serves this path")

    total = len(resolved) + len(unresolved)
    print(f"\n{len(resolved)}/{total} facade templates reach a published endpoint; "
          f"{len(new)} new break(s), {len(known)} baselined.")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
