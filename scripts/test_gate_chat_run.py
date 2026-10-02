import os
import shutil
import subprocess
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


if __name__ == "__main__":
    unittest.main()
