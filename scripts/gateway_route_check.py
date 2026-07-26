#!/usr/bin/env python3
"""#645 live check without the JVM: verify OpenAPI-discovered tools invoke through the gateway with
zero routing 404s. Python-only alternative to OpenApiToolGatewayRoutingIT for hosts with Python but
no JDK. More thorough than the IT — it checks *every* eligible discovered op, not just one.

What it does (mirrors OperationProxyFactory.executeRequest for a param-free GET): for each discovered
op (`mcp_tool.source='openapi'`) that is a GET with no path parameters, it issues
`GET {service_id}{http_path}` relaying only the Authorization header (no X-Authorities etc.), and
records the status. A gateway that matched the route returns 2xx (healthy), or 400/401/403 (routed but
rejected) — only an *unmatched* route 404s. So any 404 is a #645 routing miss.

Read-only / side-effect-free: only GETs with no `{path params}` are called (collection/list/count
endpoints), so nothing is mutated and a healthy route returns 2xx rather than a legitimate resource
404 — making a 404 unambiguous. Non-GET and parameterised ops are skipped and reported.

Config (reuses eval_live's DB/.env resolution):
  GATEWAY_URL   override the base to a host-reachable gateway (e.g. http://localhost:8080) when the
                stored service_id is a docker-internal host (http://pos-api-gateway:8080) the Python
                host can't resolve. If unset, each op's own service_id is used.
  MCP_EVAL_BEARER  optional bearer token relayed as Authorization (get a full 2xx instead of 401).
  GATEWAY_TIMEOUT  per-request timeout in seconds (default 20); raise it for slow/remote gateways.

Usage:
  ENV_FILE=/opt/durion/alpha/.env GATEWAY_URL=http://localhost:8080 \
      MCP_EVAL_BEARER=<jwt> python3 scripts/gateway_route_check.py
"""
import json
import os
import sys
import urllib.error
import urllib.request

import eval_live as E  # reuse DB_* config + .env resolution

GATEWAY_URL = os.environ.get("GATEWAY_URL", "").rstrip("/")
TIMEOUT = int(os.environ.get("GATEWAY_TIMEOUT", "20"))


def bearer():
    raw = os.environ.get("MCP_EVAL_BEARER") or E.env_file("MCP_EVAL_BEARER")
    if not raw:
        return None
    raw = raw.strip()
    return raw if raw.lower().startswith("bearer ") else "Bearer " + raw


def probe(base, path, token):
    """GET base+path relaying only Authorization. Returns (status|None, url, error|None)."""
    url = base.rstrip("/") + path
    req = urllib.request.Request(url, method="GET")
    if token:
        req.add_header("Authorization", token)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, url, None
    except urllib.error.HTTPError as e:
        return e.code, url, None  # 4xx/5xx: the route resolved and the service/gateway answered
    except Exception as e:  # URLError, timeout, DNS — infra, not a routing verdict
        return None, url, f"{type(e).__name__}: {e}"


def main():
    try:
        import pg8000.native
    except ImportError:
        sys.exit("Missing driver. Run:  pip install --user pg8000")
    if not E.DB_USER or not E.DB_PASS:
        sys.exit("Could not resolve DB user/password (env or .env). Set ENV_FILE=/opt/durion/alpha/.env")

    con = pg8000.native.Connection(
        user=E.DB_USER, password=E.DB_PASS, host=E.DB_HOST, port=E.DB_PORT, database=E.DB_NAME
    )
    token = bearer()
    print(f"DB={E.DB_USER}@{E.DB_HOST}:{E.DB_PORT}/{E.DB_NAME}  "
          f"gateway_override={GATEWAY_URL or '(use each op service_id)'}  bearer={'yes' if token else 'no'}\n")

    rows = con.run(
        """
        SELECT name, http_method, http_path, service_id
        FROM mcp_tool
        WHERE source = 'openapi' AND enabled
          AND http_path IS NOT NULL AND service_id IS NOT NULL
        ORDER BY name
        """
    )
    con.close()

    routed, not_found, unreachable = [], [], []
    skipped_write, skipped_params = 0, 0

    for name, method, path, service_id in rows:
        if (method or "").upper() != "GET":
            skipped_write += 1           # never call write ops against a live gateway
            continue
        if "{" in path:
            skipped_params += 1          # needs path values; skip for an unambiguous signal
            continue
        base = GATEWAY_URL or service_id
        status, url, err = probe(base, path, token)
        entry = {"name": name, "path": path, "url": url, "status": status}
        if err is not None:
            unreachable.append({**entry, "error": err})
        elif status == 404:
            not_found.append(entry)
        else:
            routed.append(entry)

    result = {
        "checked": len(routed) + len(not_found),
        "routed_ok": len(routed),
        "not_found_violations": not_found,     # the #645 failures
        "unreachable": unreachable,
        "skipped_write_ops": skipped_write,
        "skipped_path_param_ops": skipped_params,
        "status_breakdown": _breakdown(routed),
    }
    out = E.ROOT / "pos-mcp-server/target/eval/gateway-route-check.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    print(f"\nwrote {out}")

    if not_found:
        print(f"\n#645 FAIL: {len(not_found)} discovered op(s) 404'd through the gateway "
              f"(routing miss): {[v['name'] for v in not_found]}")
        sys.exit(1)
    if result["checked"] == 0 and unreachable:
        print("\nINCONCLUSIVE: gateway unreachable from this host — set GATEWAY_URL to a reachable "
              "gateway (e.g. http://localhost:8080).")
        sys.exit(2)
    if result["checked"] == 0:
        print("\nINCONCLUSIVE: no param-free GET openapi ops discovered to check.")
        sys.exit(2)
    print(f"\n#645 OK: {result['checked']} discovered op(s) routed through the gateway, zero 404s.")


def _breakdown(routed):
    counts = {}
    for e in routed:
        counts[str(e["status"])] = counts.get(str(e["status"]), 0) + 1
    return counts


if __name__ == "__main__":
    main()
