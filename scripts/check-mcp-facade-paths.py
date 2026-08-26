#!/usr/bin/env python3
"""Guards against pos-mcp-server facade tools pointing at endpoints that do not exist.

Every @Tool in pos-mcp-server/.../orchestration/tools/*FacadeTool.java calls a
downstream service through a `base-url` + URI template configured in
application.yml, e.g.

    product-uri-template: ${POS_CATALOG_PRODUCT_URI_TEMPLATE:/catalog/v1/catalog/products/{productId}}

Checker v2 (#1519 WS-0.2) models the whole call the way production resolves it:

1. ROUTE-AWARE MATCHING. Gateway-bound templates (base-url http://pos-api-gateway)
   are resolved through the actual route table in
   pos-api-gateway/src/main/resources/application.yml (Path=/{route}/** +
   StripPrefix=1 -> lb://SERVICE). The stripped path is matched ONLY against the
   openapi.yaml of the module the route actually targets (lb name resolved via each
   module's spring.application.name). v1 matched against ANY module's spec, which
   produced false passes such as /customer/v1/vehicles/search "matching"
   pos-vehicle-inventory while the customer route 404s it.
2. BASE-URL MODELLING. A direct (non-gateway) base-url, e.g. the ADR-0021 pos-tax
   exception http://pos-tax:8091/v1/tax, contributes its path prefix: base path +
   template is matched against that module's openapi.yaml, with no route or
   StripPrefix logic. Base-urls whose host maps to no repo module (dev's
   localhost:PORT service roots) are reported as skipped, never as breaks.
3. HTTP METHOD COMPARISON. Each template's verb is recovered from the facade Java
   source (@Value ctor param -> field -> .get()/.post()/... chain using that
   field; comments are stripped string-aware first). A path that exists but does
   not publish the verb is its own break kind: "method-mismatch".
4. PROFILE OVERLAYS. --profile {alpha|prod|dev} (default alpha, the deployed
   profile per docker-compose.yml) deep-merges the overlay's pos: block over the
   base application.yml pos: block before analysis.

Usage (from the repo root; standard library plus PyYAML, as CI already installs):

    python3 scripts/check-mcp-facade-paths.py
    python3 scripts/check-mcp-facade-paths.py --baseline scripts/mcp-facade-paths-baseline.json
    python3 scripts/check-mcp-facade-paths.py --write-baseline scripts/mcp-facade-paths-baseline.json

A baseline records templates whose breakage is known and tracked, so the check can
gate NEW breakage before the existing backlog is fixed -- same convention as
scripts/rbac-audit-baseline.json. A break is "known" only if the same property is
baselined with the same break kind; reclassification counts as new. The baseline
is a defect inventory, not an approval: entries are meant to disappear (empty by
Wave 5 of the #1519 plan).

Exit codes: 0 = no new breaks; 1 = new breaks; 2 = usage/config error.
"""
import argparse, json, pathlib, re, sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: pip install --only-binary=:all: 'pyyaml==6.0.2'")

GATEWAY_HOSTS = {"pos-api-gateway"}
VERBS = ("get", "post", "put", "delete", "patch")


def extract_default(value):
    """'${VAR:default}' -> 'default' (brace-aware: the default may contain {placeholders}
    and colons, so the closing brace is found by depth counting). Non-placeholder
    values pass through unchanged; nested defaults recurse."""
    if not isinstance(value, str):
        return value
    m = re.match(r"\$\{[A-Za-z0-9_.]+:", value)
    if not m:
        return value
    i, depth, out = m.end(), 1, []
    while i < len(value):
        c = value[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
        out.append(c)
        i += 1
    return extract_default("".join(out).strip())


def deep_merge(base, overlay):
    if not isinstance(base, dict) or not isinstance(overlay, dict):
        return overlay
    out = dict(base)
    for k, v in overlay.items():
        out[k] = deep_merge(base[k], v) if k in base else v
    return out


def flatten(node, prefix=""):
    out = {}
    for k, v in (node or {}).items():
        key = f"{prefix}.{k}" if prefix else str(k)
        if isinstance(v, dict):
            out.update(flatten(v, key))
        else:
            out[key] = v
    return out


def load_pos_block(root, profile):
    res = root / "pos-mcp-server/src/main/resources"
    base = (yaml.safe_load((res / "application.yml").read_text()) or {}).get("pos") or {}
    overlay_file = res / f"application-{profile}.yml"
    if overlay_file.exists():
        overlay = (yaml.safe_load(overlay_file.read_text()) or {}).get("pos") or {}
        base = deep_merge(base, overlay)
    return base


def strip_comments(src):
    """Remove // and /* */ comments without touching string literals."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            out.append(src[i : j + 1])
            i = j + 1
        elif src.startswith("//", i):
            i = src.find("\n", i)
            i = n if i < 0 else i
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def facade_calls(root):
    """prop -> list of (base_url_prop, verb) recovered from the facade tool sources."""
    calls = {}
    tools = root / "pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools"
    for java in sorted(tools.glob("*.java")):
        src = strip_comments(java.read_text())
        # @Value("${pos.x.y}") [annotations] String paramName  ->  param -> property
        param_prop = {
            m.group(2): m.group(1)
            for m in re.finditer(r'@Value\("\$\{([a-z0-9.\-]+)\}"\)\s*(?:@\w+\s+)*String\s+(\w+)', src)
        }
        client_base, field_prop = {}, {}
        for m in re.finditer(r"this\.(\w+)\s*=\s*ToolRestClientSupport\.instrumentedClient\([^,;]+,\s*(\w+)\)", src):
            if m.group(2) in param_prop:
                client_base[m.group(1)] = param_prop[m.group(2)]
        for m in re.finditer(r"this\.(\w+)\s*=\s*(\w+);", src):
            if m.group(2) in param_prop:
                field_prop[m.group(1)] = param_prop[m.group(2)]
        # clientField.get()... .uri(templateField ...) ... .retrieve()
        for m in re.finditer(
            r"(\w+)\s*\.\s*(" + "|".join(VERBS) + r")\s*\(\s*\)(.*?)\.\s*retrieve\s*\(", src, re.DOTALL
        ):
            client, verb, middle = m.group(1), m.group(2), m.group(3)
            if client not in client_base:
                continue
            u = re.search(r"\.\s*uri\s*\(\s*(\w+)", middle)
            if u and u.group(1) in field_prop:
                calls.setdefault(field_prop[u.group(1)], []).append((client_base[client], verb))
    return calls


def gateway_routes(root):
    """route prefix -> (service_name, strip_count)."""
    cfg = yaml.safe_load((root / "pos-api-gateway/src/main/resources/application.yml").read_text()) or {}
    node = cfg
    for k in ("spring", "cloud", "gateway", "server", "webflux", "routes"):
        node = (node or {}).get(k)
    routes = {}
    for r in node or []:
        uri = str(r.get("uri", ""))
        service = uri[len("lb://") :] if uri.startswith("lb://") else uri
        path = next((p for p in r.get("predicates", []) if str(p).startswith("Path=")), None)
        if not path:
            continue
        prefix = str(path)[len("Path=") :].split(",")[0].strip().strip("/").split("/")[0]
        strip = 0
        for f in r.get("filters", []):
            fm = re.match(r"StripPrefix=(\d+)", str(f))
            if fm:
                strip = int(fm.group(1))
        routes[prefix] = (service, strip)
    return routes


def service_modules(root):
    """lowercase spring.application.name -> module dir name, plus dir-name identity."""
    out = {}
    for f in sorted(root.glob("pos-*/src/main/resources/application.yml")):
        try:
            doc = yaml.safe_load(f.read_text()) or {}
        except yaml.YAMLError:
            continue
        name = ((doc.get("spring") or {}).get("application") or {}).get("name")
        if name:
            out[str(name).lower()] = f.parts[-5]
    for d in root.glob("pos-*/"):
        out.setdefault(d.name.lower(), d.name)
    return out


def resolve_module(name, modules, root):
    key = name.lower()
    if key in modules and (root / modules[key] / "openapi.yaml").exists():
        return modules[key]
    guess = f"pos-{key}"
    if (root / guess / "openapi.yaml").exists():
        return guess
    return None


def normalise(path):
    """/v1/products/{productId}?q=x -> /v1/products/{} — params and query are not routed."""
    return re.sub(r"\{[^}]+\}", "{}", path.split("?")[0]).rstrip("/") or "/"


def module_paths(root):
    out = {}
    for spec in sorted(root.glob("pos-*/openapi.yaml")):
        try:
            doc = yaml.safe_load(spec.read_text()) or {}
        except yaml.YAMLError as exc:
            print(f"warning: {spec} did not parse ({exc}); skipped", file=sys.stderr)
            continue
        paths = {}
        for p, ops in (doc.get("paths") or {}).items():
            verbs = {v for v in (ops or {}) if v in VERBS}
            paths.setdefault(normalise(p), set()).update(verbs)
        out[spec.parent.name] = paths
    return out


def split_base_url(base):
    m = re.match(r"(?:[a-z]+://)?([^/:\s]+)(?::\d+)?(/.*)?$", base or "")
    return (m.group(1), (m.group(2) or "").rstrip("/")) if m else (None, "")


def join_path(base_path, template):
    if template.startswith("/"):
        return base_path + template
    if template.startswith("?") or not template:
        return (base_path or "/") + template
    return base_path + "/" + template


def expansion_annotations(root):
    """Enum-valued path params, declared as a comment line directly above the template:

        # facade-checker: expand window=lastHour|lastDay|lastWeek
        summary-uri-template: ${...:/event-receiver/v1/events/summary/{window}}

    The checker then requires EVERY listed expansion to be published, instead of
    wildcard-matching the segment (wildcards would re-admit the false-pass class
    this script exists to catch). Keyed by property leaf name; applied only when
    the template actually contains the annotated {param}."""
    out = {}
    yml = root / "pos-mcp-server/src/main/resources/application.yml"
    lines = yml.read_text().splitlines()
    for i, line in enumerate(lines[:-1]):
        m = re.match(r"\s*#\s*facade-checker:\s*expand\s+([A-Za-z0-9_]+)=([A-Za-z0-9_|]+)\s*$", line)
        if not m:
            continue
        prop = re.match(r"\s*([A-Za-z0-9-]+):", lines[i + 1])
        if prop:
            out.setdefault(prop.group(1), {})[m.group(1)] = m.group(2).split("|")
    return out


def expanded_ok(rec, ann_by_leaf, published, verb):
    """True when every enum expansion of the downstream path is published (with verb)."""
    leaf = rec["property"].rsplit(".", 1)[-1]
    ann = ann_by_leaf.get(leaf)
    if not ann:
        return False
    raw = rec["downstream"].split("?")[0]
    params = re.findall(r"\{([^}]+)\}", raw)
    expandable = [q for q in params if q in ann]
    if not expandable:
        return False
    concretes = [raw]
    for q in expandable:
        concretes = [c.replace("{" + q + "}", v) for c in concretes for v in ann[q]]
    module_pub = published.get(rec["module"], {})
    for c in concretes:
        verbs = module_pub.get(normalise(c))
        if verbs is None or (verb is not None and verb not in verbs):
            return False
    return True


def evaluate(root, profile):
    pos = flatten(load_pos_block(root, profile))
    calls = facade_calls(root)
    routes = gateway_routes(root)
    modules = service_modules(root)
    published = module_paths(root)
    annotations = expansion_annotations(root)

    ok, breaks, skipped = [], [], []
    for key in sorted(k for k in pos if k.endswith("uri-template")):
        prop = f"pos.{key}"
        template = extract_default(pos[key])
        usages = calls.get(prop)
        if usages:
            base_prop, verb = usages[0]
            base = extract_default(pos.get(base_prop[len("pos.") :], ""))
        else:
            verb = None
            sibling = ".".join(key.split(".")[:-1] + ["base-url"])
            base = extract_default(pos.get(sibling, ""))
        host, base_path = split_base_url(base)
        full = join_path(base_path, template)
        rec = {"property": prop, "template": template, "base_url": base, "method": verb}

        if host in GATEWAY_HOSTS:
            segments = full.lstrip("/").split("/")
            route = segments[0] if segments else ""
            if route not in routes:
                breaks.append({**rec, "kind": "no-route", "detail": f"first segment /{route} matches no gateway route"})
                continue
            service, strip = routes[route]
            module = resolve_module(service, modules, root)
            if module is None:
                breaks.append({**rec, "kind": "no-route", "detail": f"route /{route} -> lb://{service} maps to no module openapi.yaml"})
                continue
            downstream = "/" + "/".join(segments[strip:])
            rec.update(module=module, downstream=downstream)
        else:
            module = resolve_module(host or "", modules, root)
            if module is None:
                skipped.append({**rec, "detail": f"base-url host '{host}' maps to no repo module; unresolvable here"})
                continue
            rec.update(module=module, downstream=full)

        want = normalise(rec["downstream"])
        verbs = published.get(module, {}).get(want)
        if verbs is None and expanded_ok(rec, annotations, published, verb):
            ok.append(rec)
            continue
        if verbs is None:
            others = sorted(m for m, ps in published.items() if m != module and want in ps)
            detail = f"{module}/openapi.yaml does not publish {want}"
            if others:
                detail += f" (published by {', '.join(others)} -- different route)"
            breaks.append({**rec, "kind": "path-not-published", "detail": detail})
        elif verb is not None and verb not in verbs:
            breaks.append({**rec, "kind": "method-mismatch",
                           "detail": f"{module} publishes {want} as [{', '.join(sorted(verbs))}], tool calls {verb.upper()}"})
        else:
            ok.append(rec)
    return ok, breaks, skipped


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--profile", choices=("alpha", "prod", "dev"), default="alpha")
    ap.add_argument("--baseline", help="baseline JSON; only NEW breaks fail the check")
    ap.add_argument("--write-baseline", help="write all current breaks to this baseline JSON and exit 0")
    ap.add_argument("--repo-root", default=".", help="repository root (default: cwd)")
    args = ap.parse_args()

    root = pathlib.Path(args.repo_root)
    if not (root / "pos-mcp-server").is_dir():
        sys.exit(2)
    ok, breaks, skipped = evaluate(root, args.profile)

    for rec in ok:
        verb = (rec["method"] or "?").upper()
        print(f"  ok   {verb:6s} {rec['template']}  ->  {rec['module']} {rec['downstream']}")
    for rec in skipped:
        print(f"  skip {rec['property']}: {rec['detail']}")

    if args.write_baseline:
        payload = {
            "comment": "Known-broken pos-mcp-server facade templates (#1519). Defect inventory, "
                       "not an approval: entries are removed as Waves 2-3 repoint each tool.",
            "profile": args.profile,
            "unresolved": {b["property"]: {k: v for k, v in b.items() if k != "property"} for b in breaks},
        }
        pathlib.Path(args.write_baseline).write_text(json.dumps(payload, indent=2) + "\n")
        print(f"\nwrote {len(breaks)} break(s) to {args.write_baseline}")

    baseline = {}
    if args.baseline and pathlib.Path(args.baseline).exists():
        baseline = json.load(open(args.baseline)).get("unresolved", {})
    known = [b for b in breaks if baseline.get(b["property"], {}).get("kind") == b["kind"]]
    new = [b for b in breaks if b not in known]

    if known:
        print(f"\n-- {len(known)} known-broken (baselined, tracked for fixing) --")
        for b in known:
            print(f"  known  [{b['kind']}] {b['property']}  {b['template']}")
    if new:
        print(f"\nFAIL: {len(new)} facade template(s) do not resolve to a published operation:")
        for b in new:
            print(f"  {b['property']}   [{b['kind']}]")
            print(f"      configured : {(b['method'] or '?').upper()} {b['template']}   (base-url {b['base_url']})")
            if b.get("downstream"):
                print(f"      downstream : {b['downstream']}  ->  {b.get('module')}")
            print(f"      problem    : {b['detail']}")

    total = len(ok) + len(breaks) + len(skipped)
    kinds = {}
    for b in breaks:
        kinds[b["kind"]] = kinds.get(b["kind"], 0) + 1
    kind_summary = ", ".join(f"{v} {k}" for k, v in sorted(kinds.items())) or "none"
    print(f"\n{len(ok)}/{total} facade templates resolve (profile {args.profile}); "
          f"{len(breaks)} break(s) [{kind_summary}]; {len(new)} new, {len(known)} baselined; "
          f"{len(skipped)} skipped.")
    if args.write_baseline:
        return 0
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
