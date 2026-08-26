#!/usr/bin/env python3
"""Self-test for scripts/check-mcp-facade-paths.py (checker v2, #1519 WS-0.2).

Builds a throwaway fixture repo (fake gateway route table, fake module openapi
specs, fake pos-mcp-server config + facade tool source) and asserts the checker's
v2 behaviors: route-aware matching, wrong-module detection, direct base-url
resolution (pos-tax style), no-route detection, HTTP method comparison, and
baseline semantics (old breaks suppressed, new breaks still fail).

Run from anywhere: python3 scripts/check-mcp-facade-paths-selftest.py
Exit 0 = all assertions hold; non-zero with a message otherwise. Stdlib only
(the checker itself needs PyYAML, as in CI). Spirit of mutation-check-selftest.sh.
"""
import json, pathlib, subprocess, sys, tempfile, textwrap

CHECKER = pathlib.Path(__file__).resolve().parent / "check-mcp-facade-paths.py"


def write(root, rel, content):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content))


def build_fixture(root):
    write(root, "pos-api-gateway/src/main/resources/application.yml", """\
        spring:
          application:
            name: pos-api-gateway
          cloud:
            gateway:
              server:
                webflux:
                  routes:
                    - id: alpha
                      uri: lb://ALPHA
                      predicates:
                        - Path=/alpha/**
                      filters:
                        - StripPrefix=1
                    - id: beta
                      uri: lb://BETA
                      predicates:
                        - Path=/beta/**
                      filters:
                        - StripPrefix=1
        """)
    write(root, "pos-alpha/src/main/resources/application.yml", "spring:\n  application:\n    name: alpha\n")
    write(root, "pos-alpha/openapi.yaml", """\
        paths:
          /v1/widgets/{widgetId}:
            get: {}
          /v1/only-post:
            post: {}
        """)
    write(root, "pos-beta/src/main/resources/application.yml", "spring:\n  application:\n    name: beta\n")
    write(root, "pos-beta/openapi.yaml", "paths:\n  /v1/things/search:\n    get: {}\n")
    # Direct-call service, pos-tax style: no gateway route, base-url carries a path prefix.
    write(root, "pos-tacks/src/main/resources/application.yml", "spring:\n  application:\n    name: pos-tacks\n")
    write(root, "pos-tacks/openapi.yaml", "paths:\n  /v1/tacks/calc:\n    post: {}\n")
    write(root, "pos-mcp-server/src/main/resources/application.yml", """\
        pos:
          alpha:
            base-url: ${POS_ALPHA_BASE_URL:http://pos-api-gateway}
            widget-uri-template: ${POS_ALPHA_WIDGET_URI_TEMPLATE:/alpha/v1/widgets/{widgetId}}
            wrongmod-uri-template: ${POS_ALPHA_WRONGMOD_URI_TEMPLATE:/alpha/v1/things/search?q={query}}
            postonly-uri-template: ${POS_ALPHA_POSTONLY_URI_TEMPLATE:/alpha/v1/only-post}
            noroute-uri-template: ${POS_ALPHA_NOROUTE_URI_TEMPLATE:/zeta/v1/widgets/{widgetId}}
          tacks:
            base-url: ${POS_TACKS_BASE_URL:http://pos-tacks:9999/v1/tacks}
            calc-uri-template: ${POS_TACKS_CALC_URI_TEMPLATE:/calc?amount={amount}}
        """)
    write(root, "pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/FixtureFacadeTool.java", """\
        package com.positivity.mcp.internal.orchestration.tools;
        // decoy in a comment: alphaClient.post().uri(widgetUriTemplate).retrieve()
        public class FixtureFacadeTool {
            private final RestClient alphaClient;
            private final RestClient tacksClient;
            private final String widgetUriTemplate;
            private final String wrongmodUriTemplate;
            private final String postonlyUriTemplate;
            private final String norouteUriTemplate;
            private final String calcUriTemplate;
            public FixtureFacadeTool(
                    RestClient.Builder builder,
                    @Value("${pos.alpha.base-url}") String alphaBaseUrl,
                    @Value("${pos.tacks.base-url}") String tacksBaseUrl,
                    @Value("${pos.alpha.widget-uri-template}") String widgetUriTemplate,
                    @Value("${pos.alpha.wrongmod-uri-template}") String wrongmodUriTemplate,
                    @Value("${pos.alpha.postonly-uri-template}") String postonlyUriTemplate,
                    @Value("${pos.alpha.noroute-uri-template}") String norouteUriTemplate,
                    @Value("${pos.tacks.calc-uri-template}") String calcUriTemplate) {
                this.alphaClient = ToolRestClientSupport.instrumentedClient(builder, alphaBaseUrl);
                this.tacksClient = ToolRestClientSupport.instrumentedClient(builder, tacksBaseUrl);
                this.widgetUriTemplate = widgetUriTemplate;
                this.wrongmodUriTemplate = wrongmodUriTemplate;
                this.postonlyUriTemplate = postonlyUriTemplate;
                this.norouteUriTemplate = norouteUriTemplate;
                this.calcUriTemplate = calcUriTemplate;
            }
            public String getWidget(String id) {
                return alphaClient.get().uri(widgetUriTemplate, Map.of("widgetId", id)).retrieve().body(String.class);
            }
            public String searchWrongModule(String q) {
                return alphaClient.get().uri(wrongmodUriTemplate, Map.of("query", q)).retrieve().body(String.class);
            }
            public String hitPostOnlyWithGet() {
                return alphaClient.get().uri(postonlyUriTemplate).retrieve().body(String.class);
            }
            public String hitNoRoute(String id) {
                return alphaClient.get().uri(norouteUriTemplate, Map.of("widgetId", id)).retrieve().body(String.class);
            }
            public String calc(String amount) {
                return tacksClient.post().uri(calcUriTemplate, Map.of("amount", amount)).retrieve().body(String.class);
            }
        }
        """)


def run(root, *extra):
    return subprocess.run(
        [sys.executable, str(CHECKER), "--repo-root", str(root), *extra],
        capture_output=True, text=True)


def check(label, cond, out):
    if not cond:
        sys.exit(f"SELFTEST FAIL [{label}]\n--- checker output ---\n{out.stdout}\n{out.stderr}")
    print(f"  pass  {label}")


def main():
    with tempfile.TemporaryDirectory(prefix="mcp-facade-selftest-") as tmp:
        root = pathlib.Path(tmp)
        build_fixture(root)

        r = run(root)
        check("exit 1 with unbaselined breaks", r.returncode == 1, r)
        check("route-aware gateway match ok",
              "ok   GET    /alpha/v1/widgets/{widgetId}  ->  pos-alpha /v1/widgets/{widgetId}" in r.stdout, r)
        check("wrong-module path is a break (route-aware, not any-module)",
              "pos.alpha.wrongmod-uri-template   [path-not-published]" in r.stdout
              and "published by pos-beta" in r.stdout, r)
        check("method mismatch detected as its own kind",
              "pos.alpha.postonly-uri-template   [method-mismatch]" in r.stdout
              and "publishes /v1/only-post as [post], tool calls GET" in r.stdout, r)
        check("unrouted first segment reported as no-route",
              "pos.alpha.noroute-uri-template   [no-route]" in r.stdout, r)
        check("direct base-url (tax-style) resolves prefix + template",
              "ok   POST   /calc?amount={amount}  ->  pos-tacks /v1/tacks/calc" in r.stdout, r)
        check("counts: 2 ok, 3 breaks",
              "2/5 facade templates resolve" in r.stdout and "3 break(s)" in r.stdout, r)

        baseline = root / "baseline.json"
        r = run(root, "--write-baseline", str(baseline))
        check("--write-baseline exits 0 and records all breaks",
              r.returncode == 0 and len(json.loads(baseline.read_text())["unresolved"]) == 3, r)

        r = run(root, "--baseline", str(baseline))
        check("baseline suppresses known breaks (exit 0, 0 new)",
              r.returncode == 0 and "0 new, 3 baselined" in r.stdout, r)

        # Introduce a NEW break: repoint the good widget template at a path nobody publishes.
        cfg = root / "pos-mcp-server/src/main/resources/application.yml"
        cfg.write_text(cfg.read_text().replace("/alpha/v1/widgets/{widgetId}}", "/alpha/v1/nonexistent/{widgetId}}"))
        r = run(root, "--baseline", str(baseline))
        check("baseline does NOT suppress a new break (exit 1, 1 new)",
              r.returncode == 1 and "1 new, 3 baselined" in r.stdout, r)

        # Profile overlay: alpha overlay repoints tacks at the gateway with no matching route.
        (root / "pos-mcp-server/src/main/resources/application-alpha.yml").write_text(
            "pos:\n  tacks:\n    base-url: ${POS_TACKS_BASE_URL:http://pos-api-gateway}\n")
        r = run(root, "--profile", "alpha")
        check("profile overlay deep-merges over the base pos block",
              "pos.tacks.calc-uri-template   [no-route]" in r.stdout, r)
    print("SELFTEST OK")


if __name__ == "__main__":
    main()
