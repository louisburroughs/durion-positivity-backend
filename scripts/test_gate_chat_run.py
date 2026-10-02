import json
import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "scripts", "gate_chat_run.sh")
EVAL = os.path.join(ROOT, "pos-mcp-server", "src", "test", "resources", "eval")
TAGGING = os.path.join(EVAL, "tagging-gate", "en.json")
RAG = os.path.join(EVAL, "rag-lexical", "codes-catalogs.json")
RAG_RETRIEVAL = os.path.join(EVAL, "rag-retrieval", "seed.json")


def dry_run(*args):
    # --dry-run exits before any curl, so this never touches the network.
    env = {k: v for k, v in os.environ.items() if k != "GATE_PASSWORD"}
    return subprocess.run(["bash", SCRIPT, "--dry-run", *args], capture_output=True, text=True, env=env)


def plan(proc):
    rows = []
    for line in proc.stdout.splitlines():
        if line.startswith("PLAN "):
            rows.append(dict(kv.split("=", 1) for kv in line.split()[1:]))
    return rows


@unittest.skipUnless(shutil.which("jq"), "jq required")
class GateChatRunPlanTest(unittest.TestCase):
    def test_tagging_gate_shape_batches(self):
        proc = dry_run("--fixture", TAGGING, "--label", "m")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        rows = plan(proc)
        total = int(rows[0]["total"])
        self.assertGreater(total, 150)
        bounds = [(int(r["start"]), int(r["end"])) for r in rows]
        self.assertEqual(bounds[0], (0, 150))
        self.assertEqual(bounds[-1][1], total)
        for (_, e), (s, _) in zip(bounds, bounds[1:]):
            self.assertEqual(e, s)
        self.assertTrue(all(int(r["count"]) < 200 for r in rows))
        self.assertEqual([int(r["batch"]) for r in rows], list(range(1, len(rows) + 1)))

    def test_rag_fixture_shapes(self):
        for path in (RAG, RAG_RETRIEVAL):
            proc = dry_run("--fixture", path, "--batch-size", "2")
            self.assertEqual(proc.returncode, 0, proc.stderr)
            rows = plan(proc)
            total = int(rows[0]["total"])
            self.assertGreater(total, 2, path)
            self.assertEqual(rows[-1]["end"], str(total))
            self.assertEqual(len(rows), -(-total // 2))

    def test_multiple_fixtures_and_label_in_out_dir(self):
        proc = dry_run("--fixture", RAG, "--fixture", RAG_RETRIEVAL, "--label", "jev-latest")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual({r["fixture"] for r in plan(proc)}, {RAG, RAG_RETRIEVAL})
        self.assertIn("gate-runs/jev-latest-", proc.stdout)

    def test_zero_padded_batch_size_is_decimal(self):
        # 08 is not octal: the plan still has batches of eight.
        proc = dry_run("--fixture", TAGGING, "--batch-size", "08")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(plan(proc)[0]["count"], "8")

    def test_cap_validation_rejects_200_and_above(self):
        for size in ("200", "500", "0", "abc"):
            proc = dry_run("--fixture", TAGGING, "--batch-size", size)
            self.assertEqual(proc.returncode, 2, size)
            self.assertIn("--batch-size", proc.stderr)
        self.assertEqual(dry_run("--fixture", TAGGING, "--batch-size", "199").returncode, 0)

    def test_custom_messages_jq(self):
        proc = dry_run("--fixture", RAG, "--messages-jq", ".fixtures[].notes")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertGreater(int(plan(proc)[0]["total"]), 0)

    def test_requires_fixture_and_rejects_unreadable(self):
        self.assertEqual(dry_run().returncode, 2)
        self.assertEqual(dry_run("--fixture", "/nonexistent.json").returncode, 2)

    def test_dry_run_needs_no_password(self):
        self.assertEqual(dry_run("--fixture", RAG).returncode, 0)


# Stands in for curl on PATH: logs its argv, answers the login with a token (recording the body it
# was sent on stdin), the chat with an HTTP code chosen by the message, and the trace export with
# as many traces as FAKE_TRACE_COUNTS names for that call.
FAKE_CURL = textwrap.dedent("""\
    #!/usr/bin/env python3
    import json, os, sys
    state = os.environ["FAKE_STATE"]
    args = sys.argv[1:]
    with open(os.path.join(state, "argv.log"), "a") as f:
        f.write(json.dumps(["curl", *args]) + "\\n")
    url = next(a for a in args if a.startswith("http://"))
    if url.endswith("/auth/login"):
        with open(os.path.join(state, "logins.log"), "a") as f:
            f.write(json.dumps(json.loads(sys.stdin.read())) + "\\n")
        print(json.dumps({"accessToken": "tok"}))
    elif url.endswith("/v1/mcp/chat"):
        message = json.loads(sys.stdin.read())["message"]
        if "DROP" in message:
            sys.stdout.write("000")
            sys.stderr.write("curl: (7) Failed to connect\\n")
            sys.exit(7)
        sys.stdout.write("500" if "FAIL500" in message else "200")
    else:
        counter = os.path.join(state, "exports")
        n = int(open(counter).read()) if os.path.exists(counter) else 0
        open(counter, "w").write(str(n + 1))
        count = int(os.environ["FAKE_TRACE_COUNTS"].split(",")[n])
        print(json.dumps([{"turnId": str(k)} for k in range(count)]))
    """)

# Stands in for jq on PATH: logs its argv, then runs the real jq.
FAKE_JQ = textwrap.dedent("""\
    #!/usr/bin/env bash
    printf '%s\\n' "jq $*" >> "$FAKE_STATE/argv.log"
    exec "$REAL_JQ" "$@"
    """)


@unittest.skipUnless(shutil.which("jq"), "jq required")
class GateChatRunNetworkTest(unittest.TestCase):
    PASSWORD = "s3cret-pw"

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        self.state = os.path.join(self.tmp, "state")
        bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(self.state)
        os.makedirs(bin_dir)
        for name, body in (("curl", FAKE_CURL), ("jq", FAKE_JQ)):
            path = os.path.join(bin_dir, name)
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)
        self.env = dict(os.environ, PATH=bin_dir + os.pathsep + os.environ["PATH"],
                        FAKE_STATE=self.state, REAL_JQ=shutil.which("jq"),
                        GATE_PASSWORD=self.PASSWORD)

    def fixture(self, subdir, utterances):
        os.makedirs(os.path.join(self.tmp, subdir))
        path = os.path.join(self.tmp, subdir, "seed.json")
        with open(path, "w") as f:
            json.dump({"utterances": [{"id": i, "text": t} for i, t in utterances]}, f)
        return path

    def run_gate(self, trace_counts, *args):
        out_dir = os.path.join(self.tmp, "out")
        env = dict(self.env, FAKE_TRACE_COUNTS=trace_counts)
        proc = subprocess.run(
            ["bash", SCRIPT, "--sleep", "0", "--out-dir", out_dir, "--chat-url", "http://fake/mcp-server/v1/mcp/chat",
             "--login-url", "http://fake/security-service/v1/auth/login", *args],
            capture_output=True, text=True, env=env)
        return proc, out_dir

    def read(self, *parts):
        with open(os.path.join(*parts)) as f:
            return f.read()

    def test_batches_failures_cap_and_manifest(self):
        # Two fixtures share a basename; the second batch of the first is capped; one turn gets an
        # HTTP 500 and one a transport failure.
        first = self.fixture("a", [("a1", "hello"), ("a2", "FAIL500 please"), ("a3", "bye")])
        second = self.fixture("b", [("b1", "DROP me")])
        proc, out = self.run_gate("2,200,1", "--batch-size", "2", "--fixture", first, "--fixture", second)

        self.assertEqual(proc.returncode, 1, proc.stderr)
        files = sorted(f for f in os.listdir(out) if f.startswith("traces-"))
        # Distinct names per fixture; the capped export was deleted and the run went on.
        self.assertEqual(files, ["traces-run-seed-f1-1.json", "traces-run-seed-f2-1.json"])

        log = self.read(out, "run.log")
        self.assertIn("turn failed: HTTP 500 id=a2 fixture=seed batch=1", log)
        self.assertRegex(log, r"turn failed: HTTP 000 id=b1 ")
        self.assertIn("CAP HIT: seed batch 2 exported 200 traces", log)

        manifest = json.loads(self.read(out, "manifest.json"))
        self.assertEqual(manifest["fixtures"], [first, second])
        self.assertEqual(manifest["batchSize"], 2)
        self.assertEqual(
            [(b["fixture"], b["batch"], b["start"], b["end"], b["failures"], b["traces"], b["capHit"], b["exportFile"])
             for b in manifest["batches"]],
            [(first, 1, 0, 2, 1, 2, False, "traces-run-seed-f1-1.json"),
             (first, 2, 2, 3, 0, 200, True, None),
             (second, 1, 0, 1, 1, 1, False, "traces-run-seed-f2-1.json")])

        # A fresh token before every batch and every export; the password travels on stdin only.
        logins = [json.loads(line) for line in self.read(self.state, "logins.log").splitlines()]
        self.assertEqual(len(logins), 6)
        self.assertTrue(all(body == {"username": "admin.alpha", "password": self.PASSWORD} for body in logins))
        self.assertNotIn(self.PASSWORD, self.read(self.state, "argv.log"))
        self.assertNotIn(self.PASSWORD, log)
        self.assertNotIn(self.PASSWORD, proc.stdout + proc.stderr)

    def test_clean_run_exits_zero(self):
        fixture = self.fixture("a", [("a1", "hello"), ("a2", "bye")])
        proc, out = self.run_gate("2", "--fixture", fixture, "--label", "jev")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(os.path.exists(os.path.join(out, "traces-jev-seed-f1-1.json")))
        self.assertIn("finished status=0", self.read(out, "run.log"))

    def test_missing_password_stops_before_any_call(self):
        fixture = self.fixture("a", [("a1", "hello")])
        env_without = {k: v for k, v in self.env.items() if k != "GATE_PASSWORD"}
        self.env = env_without
        proc, _ = self.run_gate("1", "--fixture", fixture)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("GATE_PASSWORD", proc.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.state, "logins.log")))


if __name__ == "__main__":
    unittest.main()
