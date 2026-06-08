import os
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_SCRIPT = REPO_ROOT / "scripts" / "generate-permissions.sh"


class GeneratePermissionsWrapperTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.scripts_dir = self.root / "scripts"
        self.scripts_dir.mkdir(parents=True)
        shutil.copy2(SOURCE_SCRIPT, self.scripts_dir / "generate-permissions.sh")
        os.chmod(
            self.scripts_dir / "generate-permissions.sh",
            stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR,
        )

        self.log_path = self.root / "execution.log"
        self._write_fake_generator()
        self._write_fake_exporter()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_generates_aggregate_report_after_module_permissions(self) -> None:
        result = subprocess.run(
            ["bash", str(self.scripts_dir / "generate-permissions.sh")],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertEqual(
            self.log_path.read_text(encoding="utf-8").splitlines(),
            [
                "generate",
                "export --output docs/permissions-report.yaml --root "
                + str(self.root.resolve()),
            ],
        )
        self.assertTrue((self.root / "docs" / "permissions-report.yaml").exists())

    def test_dry_run_skips_aggregate_export(self) -> None:
        result = subprocess.run(
            ["bash", str(self.scripts_dir / "generate-permissions.sh"), "--dry-run"],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertEqual(
            self.log_path.read_text(encoding="utf-8").splitlines(),
            ["generate"],
        )
        self.assertFalse((self.root / "docs" / "permissions-report.yaml").exists())

    def _write_fake_generator(self) -> None:
        script = textwrap.dedent(
            f"""\
            #!/usr/bin/env python3
            from pathlib import Path

            root = Path(__file__).resolve().parents[1]
            log_path = root / "execution.log"
            log_path.write_text("generate\\n", encoding="utf-8")

            permissions_path = root / "pos-demo" / "src" / "main" / "resources" / "permissions.yaml"
            permissions_path.parent.mkdir(parents=True, exist_ok=True)
            permissions_path.write_text("permissions: []\\n", encoding="utf-8")
            """
        )
        self._write_script(self.scripts_dir / "generate-permissions.py", script)

    def _write_fake_exporter(self) -> None:
        script = textwrap.dedent(
            """\
            #!/usr/bin/env python3
            import argparse
            from pathlib import Path

            parser = argparse.ArgumentParser()
            parser.add_argument("-o", "--output", required=True)
            parser.add_argument("--root", required=True)
            args = parser.parse_args()

            root = Path(args.root).resolve()
            log_path = root / "execution.log"
            with log_path.open("a", encoding="utf-8") as handle:
                handle.write(f"export --output {args.output} --root {root}\\n")

            output_path = root / args.output
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text("aggregate: true\\n", encoding="utf-8")
            """
        )
        self._write_script(self.scripts_dir / "export-permission-registrations-yaml.py", script)

    def _write_script(self, path: Path, contents: str) -> None:
        path.write_text(contents, encoding="utf-8")
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)


if __name__ == "__main__":
    unittest.main()
