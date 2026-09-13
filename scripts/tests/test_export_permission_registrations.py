"""The aggregate permission report must count each module once, not once per checkout.

A git worktree or nested clone holds a complete copy of the module tree. Before this was
guarded, running the exporter from a checkout with .worktrees/ populated walked into it and
counted every manifest twice: the committed docs/permissions-report.yaml described 47
manifests and 965 permissions with 470 "duplicate" names, none of which were real.
"""

import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
EXPORTER = REPO_ROOT / "scripts" / "export-permission-registrations-yaml.py"

MANIFEST = textwrap.dedent(
    """\
    domain: alpha
    serviceName: pos-alpha
    version: "1.0"
    permissions:
      - name: "alpha:thing:view"
        description: "View a thing"
      - name: "alpha:thing:edit"
        description: "Edit a thing"
    """
)


class ExportPermissionRegistrationsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.addCleanup(self.temp_dir.cleanup)
        self._write_manifest(self.root / "pos-alpha")

    def _write_manifest(self, module_dir: Path) -> None:
        resources = module_dir / "src" / "main" / "resources"
        resources.mkdir(parents=True)
        (resources / "permissions.yaml").write_text(MANIFEST, encoding="utf-8")

    def _run(self) -> dict:
        output = self.root / "report.yaml"
        result = subprocess.run(
            [sys.executable, str(EXPORTER), "--root", str(self.root), "--output", str(output)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        return yaml.safe_load(output.read_text(encoding="utf-8"))

    def _summary(self) -> dict:
        return self._run()["summary"]

    def test_countsTheModuleOnce_whenNoCheckoutsAreNested(self) -> None:
        summary = self._summary()
        self.assertEqual(summary["manifestCount"], 1)
        self.assertEqual(summary["permissionCount"], 2)
        self.assertEqual(summary["duplicatePermissionNameCount"], 0)

    def test_ignoresAWorktreeUnderTheConventionalDirectory(self) -> None:
        worktree = self.root / ".worktrees" / "feat-branch"
        worktree.mkdir(parents=True)
        # A worktree's .git is a file pointing at the real repository, not a directory.
        (worktree / ".git").write_text("gitdir: /somewhere/.git/worktrees/feat-branch\n", encoding="utf-8")
        self._write_manifest(worktree / "pos-alpha")

        summary = self._summary()
        self.assertEqual(summary["manifestCount"], 1)
        self.assertEqual(summary["duplicatePermissionNameCount"], 0)

    def test_ignoresANestedCheckoutWhateverItIsNamed(self) -> None:
        # The guard is the .git entry, not the directory name: a worktree parked somewhere
        # other than .worktrees/ has to be skipped too.
        nested = self.root / "scratch" / "review-copy"
        nested.mkdir(parents=True)
        (nested / ".git").mkdir()
        self._write_manifest(nested / "pos-alpha")

        summary = self._summary()
        self.assertEqual(summary["manifestCount"], 1)
        self.assertEqual(summary["duplicatePermissionNameCount"], 0)

    def test_stillFindsASecondRealModule(self) -> None:
        # The pruning must not be so eager that it hides modules that genuinely exist.
        second = self.root / "pos-beta"
        resources = second / "src" / "main" / "resources"
        resources.mkdir(parents=True)
        (resources / "permissions.yaml").write_text(
            MANIFEST.replace("alpha", "beta"), encoding="utf-8"
        )

        summary = self._summary()
        self.assertEqual(summary["manifestCount"], 2)
        self.assertEqual(summary["permissionCount"], 4)
        self.assertEqual(summary["duplicatePermissionNameCount"], 0)


if __name__ == "__main__":
    unittest.main()
