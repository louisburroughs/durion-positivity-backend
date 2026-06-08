import importlib.util
import tempfile
import textwrap
import unittest
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "scripts" / "generate-permissions.py"


def load_generator_module():
    spec = importlib.util.spec_from_file_location("generate_permissions", MODULE_PATH)
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


if __name__ == "__main__":
    unittest.main()