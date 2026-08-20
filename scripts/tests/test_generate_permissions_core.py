import importlib.util
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "scripts" / "generate-permissions.py"
SANITIZER_PATH = REPO_ROOT / "scripts" / "sanitize-openapi.py"


def load_generator_module():
    spec = importlib.util.spec_from_file_location("generate_permissions", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec is not None and spec.loader is not None
    spec.loader.exec_module(module)
    return module


def load_sanitizer_module():
    spec = importlib.util.spec_from_file_location("sanitize_openapi", SANITIZER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec is not None and spec.loader is not None
    spec.loader.exec_module(module)
    return module


class GeneratePermissionsCoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.generator = load_generator_module()

    def test_permission_belongs_to_domain_handles_mixed_case_and_aliases(self) -> None:
        self.assertTrue(
            self.generator.permission_belongs_to_domain(
                "Work-Order:estimate:view", "workorder"
            )
        )
        self.assertTrue(
            self.generator.permission_belongs_to_domain(
                "work_order:estimate:view", "Work-Order"
            )
        )
        self.assertFalse(
            self.generator.permission_belongs_to_domain(
                "inventory:item:view", "workorder"
            )
        )

    def test_scan_module_splits_own_and_cross_domain_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            module_path = Path(temp_dir) / "pos-demo"
            java_path = (
                module_path
                / "src"
                / "main"
                / "java"
                / "com"
                / "positivity"
                / "demo"
                / "internal"
                / "controller"
                / "DemoController.java"
            )
            java_path.parent.mkdir(parents=True, exist_ok=True)
            java_path.write_text(
                textwrap.dedent(
                    """\
                    package com.positivity.demo.internal.controller;

                    import org.springframework.security.access.prepost.PreAuthorize;

                    class DemoController {
                        @PreAuthorize("hasAnyAuthority('Work-Order:estimate:view', 'inventory:item:view')")
                        void sample() {}
                    }
                    """
                ),
                encoding="utf-8",
            )

            own, cross = self.generator.scan_module(module_path, "workorder")

            self.assertEqual(own, {"Work-Order:estimate:view"})
            self.assertEqual(cross, {"inventory:item:view"})

    def test_write_permissions_yaml_escapes_control_like_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "permissions.yaml"
            self.generator.write_permissions_yaml(
                output,
                "workorder",
                "pos-workorder",
                "1.0",
                [
                    {
                        "name": "workorder:notes:update",
                        "description": "line1\nline2\ttab\u0001control",
                    }
                ],
            )

            parsed = yaml.safe_load(output.read_text(encoding="utf-8"))
            self.assertEqual(parsed["domain"], "workorder")
            self.assertEqual(parsed["serviceName"], "pos-workorder")
            self.assertEqual(parsed["permissions"][0]["name"], "workorder:notes:update")
            self.assertEqual(
                parsed["permissions"][0]["description"],
                "line1\nline2\ttab\u0001control",
            )

    def test_sync_repairs_stale_downstream_catalog_without_new_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            downstream_path = self._write_catalog_fixture(root)

            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), str(root), "--sync"],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(result.returncode, 0, msg=result.stderr)
            downstream = downstream_path.read_text(encoding="utf-8")
            self.assertIn("public static final int CATALOG_VERSION = 2;", downstream)
            self.assertIn('"PERM_demo:edit" // 1', downstream)
            self.assertIn("DownstreamPermissionCatalog.java: repaired", result.stdout)

    def test_sync_check_rejects_stale_downstream_catalog_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            downstream_path = self._write_catalog_fixture(root)
            original = downstream_path.read_text(encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), str(root), "--sync", "--check"],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(result.returncode, 1)
            self.assertEqual(downstream_path.read_text(encoding="utf-8"), original)
            self.assertIn("DownstreamPermissionCatalog.java is out of sync", result.stderr)

    def _write_catalog_fixture(self, root: Path) -> Path:
        permission_code_path = root / self.generator.PERMISSION_CODE_RELPATH
        permission_code_path.parent.mkdir(parents=True, exist_ok=True)
        permission_code_path.write_text(
            textwrap.dedent(
                """\
                public enum PermissionCode {
                    DEMO__VIEW(0, "demo:view"),
                    DEMO__EDIT(1, "demo:edit");

                    public static final int CATALOG_VERSION = 2;
                }
                """
            ),
            encoding="utf-8",
        )

        gateway_path = root / self.generator.GATEWAY_CATALOG_RELPATH
        gateway_path.parent.mkdir(parents=True, exist_ok=True)
        gateway_path.write_text(
            textwrap.dedent(
                """\
                public final class GatewayPermissionCatalog {
                    public static final int CATALOG_VERSION = 2;
                    static final String[] AUTHORITY_BY_BIT = {
                        "PERM_demo:view",
                        "PERM_demo:edit"
                    };
                }
                """
            ),
            encoding="utf-8",
        )

        downstream_path = root / self.generator.DOWNSTREAM_CATALOG_RELPATH
        downstream_path.parent.mkdir(parents=True, exist_ok=True)
        downstream_path.write_text(
            textwrap.dedent(
                """\
                public final class DownstreamPermissionCatalog {
                    public static final int CATALOG_VERSION = 1;
                    static final String[] AUTHORITY_BY_BIT = {
                        "PERM_demo:view"
                    };
                }
                """
            ),
            encoding="utf-8",
        )

        java_path = root / "pos-demo/src/main/java/DemoController.java"
        java_path.parent.mkdir(parents=True, exist_ok=True)
        java_path.write_text(
            """@PreAuthorize("hasAnyAuthority('demo:view', 'demo:edit')")\nclass DemoController {}\n""",
            encoding="utf-8",
        )
        return downstream_path


class SanitizeOpenApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sanitizer = load_sanitizer_module()

    def test_sanitize_file_is_deterministic_for_mapping_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "openapi.yaml"
            output.write_text(
                """components:\n  schemas:\n    Zulu:\n      type: object\n      properties:\n        zebra:\n          type: string\n        alpha:\n          type: string\n    Alpha:\n      type: object\n      properties:\n        beta:\n          type: string\npaths:\n  /z:\n    get:\n      responses:\n        '200':\n          description: ok\n  /a:\n    get:\n      responses:\n        '200':\n          description: ok\nopenapi: 3.1.0\ninfo:\n  version: v1\n  title: Test\n""",
                encoding="utf-8",
            )

            self.assertTrue(self.sanitizer.sanitize_file(output))
            first = output.read_text(encoding="utf-8")
            self.assertFalse(self.sanitizer.sanitize_file(output))
            self.assertEqual(first, output.read_text(encoding="utf-8"))

            parsed = yaml.safe_load(first)
            self.assertEqual(list(parsed), ["openapi", "info", "paths", "components"])
            self.assertEqual(list(parsed["paths"]), ["/a", "/z"])
            self.assertEqual(list(parsed["components"]["schemas"]), ["Alpha", "Zulu"])
            self.assertEqual(
                list(parsed["components"]["schemas"]["Zulu"]["properties"]),
                ["alpha", "zebra"],
            )


if __name__ == "__main__":
    unittest.main()