import importlib.util
import re
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

    def test_sanitize_file_keeps_iso_timestamp_examples_as_strings(self) -> None:
        """Issue #1764: an ISO 8601 example must survive the round-trip verbatim.

        PyYAML's safe_load resolves a plain scalar like 2026-03-17T14:30:00.000Z to a
        datetime, and safe_dump then re-emits it as "2026-03-17 14:30:00+00:00" — a
        space instead of the T, the milliseconds gone, and no longer parsable as
        RFC 3339. springdoc emits the example correctly; this script was destroying
        it, in every module spec, for ApiError.timestamp.
        """
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "openapi.yaml"
            output.write_text(
                "openapi: 3.1.0\n"
                "info:\n  version: v1\n  title: Test\n"
                "components:\n"
                "  schemas:\n"
                "    ApiError:\n"
                "      type: object\n"
                "      properties:\n"
                "        timestamp:\n"
                "          type: string\n"
                "          example: 2026-03-17T14:30:00.000Z\n",
                encoding="utf-8",
            )

            self.sanitizer.sanitize_file(output)
            text = output.read_text(encoding="utf-8")

            # Deliberately a plain yaml.safe_load, with the timestamp resolver still in place:
            # it can only return a str if the sanitizer quoted the scalar on the way out. That is
            # the property worth pinning, and it does not depend on WHICH quoting style PyYAML
            # picks — asserting the literal "'...'" would couple the test to the emitter.
            example = yaml.safe_load(text)["components"]["schemas"]["ApiError"]["properties"][
                "timestamp"
            ]["example"]
            self.assertIsInstance(example, str)
            self.assertEqual(example, "2026-03-17T14:30:00.000Z")

            # And it is a fixed point: sanitizing again neither changes the file nor re-resolves
            # the scalar, so repeated generation runs cannot walk it back to a datetime.
            self.assertFalse(self.sanitizer.sanitize_file(output))
            self.assertEqual(text, output.read_text(encoding="utf-8"))

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


SEED_SQL_TEMPLATE = """\
-- Repeatable seed: canonical role -> permission baseline (role_permissions).
INSERT INTO permissions (
    id, name, description, domain, resource, action,
    registered_at, registered_by_service, version, bit_index)
SELECT gen_random_uuid(), c.name, c.name, c.domain, c.resource, c.action,
       NOW(), 'pos-security-service', '1.0', c.bit_index
FROM (VALUES
    ('catalog:product:view', 'catalog', 'product', 'view', 1),
    ('catalog:zebra:view', 'catalog', 'zebra', 'view', 2),
    ('workorder:note:add', 'workorder', 'note', 'add', 3)
) AS c(name, domain, resource, action, bit_index)
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('ADMIN', 'catalog:product:view'),
    ('ADMIN', 'catalog:zebra:view'),
    ('ADMIN', 'workorder:note:add'),
    ('SHOP_MANAGER', 'catalog:product:view')
) AS g(role_name, permission_name)
JOIN roles r ON r.name = g.role_name
JOIN permissions p ON p.name = g.permission_name
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    missing_permissions TEXT;
BEGIN
    SELECT string_agg(DISTINCT g.permission_name, ', ' ORDER BY g.permission_name)
      INTO missing_permissions
      FROM (VALUES
        ('catalog:product:view'),
        ('catalog:zebra:view'),
        ('workorder:note:add')
      ) AS g(permission_name)
     WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.name = g.permission_name);
END $$;
"""

CSV_TEMPLATE = """\
roleName,permissions
ADMIN,catalog:product:view;catalog:zebra:view;workorder:note:add
SERVICE_ADVISOR,catalog:product:view
SHOP_MANAGER,catalog:product:view
"""


class GrantSourceSyncTest(unittest.TestCase):
    """#1848: --sync must land the grant, not just the bit."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.generator = load_generator_module()

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.seed_path = self.root / self.generator.SEED_SQL_RELPATH
        self.seed_path.parent.mkdir(parents=True, exist_ok=True)
        self.seed_path.write_text(SEED_SQL_TEMPLATE, encoding="utf-8")
        self.csv_path = self.root / self.generator.ROLE_PERMISSIONS_CSV_RELPATH
        self.csv_path.parent.mkdir(parents=True, exist_ok=True)
        self.csv_path.write_text(CSV_TEMPLATE, encoding="utf-8")

    def sync(self, grants: dict[str, list[str]], bits: dict[str, int], dry_run: bool = False):
        seed_messages = self.generator.sync_seed_sql(self.root, grants, bits, dry_run)
        csv_messages = self.generator.sync_role_permissions_csv(self.root, grants, dry_run)
        return seed_messages + csv_messages

    def seed_rows(self, marker_attr: str, key_fn_name: str) -> list[str]:
        text = self.seed_path.read_text(encoding="utf-8")
        marker = getattr(self.generator, marker_attr)
        start, end = self.generator._values_block_bounds(text, marker)
        key_of = getattr(self.generator, key_fn_name)
        return [k for k in (key_of(line) for line in text[start:end].split("\n")) if k is not None]

    def csv_row(self, role: str) -> list[str]:
        return self.generator.parse_baseline_grants(self.root)[role]

    def test_split_permission_handles_two_and_three_segment_codes(self) -> None:
        self.assertEqual(
            self.generator.split_permission("catalog:tread_design:resolve"),
            ("catalog", "tread_design", "resolve"),
        )
        # Two-segment codes carry an empty resource, matching the rows already in the seed.
        self.assertEqual(self.generator.split_permission("appointments:cancel"), ("appointments", "", "cancel"))
        with self.assertRaises(ValueError):
            self.generator.split_permission("nocolonhere")

    def test_seed_grant_lands_row_grant_and_self_check_in_sorted_position(self) -> None:
        self.sync({"catalog:tread_design:resolve": ["ADMIN"]}, {"catalog:tread_design:resolve": 42})

        self.assertEqual(
            self.seed_rows("SEED_PERMISSION_ROWS_MARKER", "_permission_row_key"),
            ["catalog:product:view", "catalog:tread_design:resolve", "catalog:zebra:view", "workorder:note:add"],
        )
        self.assertEqual(
            self.seed_rows("SEED_GRANT_ROWS_MARKER", "_grant_row_key"),
            [
                ("ADMIN", "catalog:product:view"),
                ("ADMIN", "catalog:tread_design:resolve"),
                ("ADMIN", "catalog:zebra:view"),
                ("ADMIN", "workorder:note:add"),
                ("SHOP_MANAGER", "catalog:product:view"),
            ],
        )
        self.assertEqual(
            self.seed_rows("SEED_SELF_CHECK_MARKER", "_self_check_row_key"),
            ["catalog:product:view", "catalog:tread_design:resolve", "catalog:zebra:view", "workorder:note:add"],
        )
        self.assertIn(
            "    ('catalog:tread_design:resolve', 'catalog', 'tread_design', 'resolve', 42),",
            self.seed_path.read_text(encoding="utf-8"),
        )
        self.assertEqual(
            self.csv_row("ADMIN"),
            ["catalog:product:view", "catalog:tread_design:resolve", "catalog:zebra:view", "workorder:note:add"],
        )

    def test_appending_past_the_last_row_keeps_the_comma_grammar(self) -> None:
        # The last row of a VALUES list carries no trailing comma; appending after it has to
        # give the old last row one, or the migration no longer parses.
        self.sync({"zz:last:code": ["ADMIN"]}, {"zz:last:code": 99})

        text = self.seed_path.read_text(encoding="utf-8")
        self.assertIn("    ('workorder:note:add', 'workorder', 'note', 'add', 3),\n", text)
        self.assertIn("    ('zz:last:code', 'zz', 'last', 'code', 99)\n", text)
        # The grant block is keyed on (role, permission), so ADMIN's new row lands mid-list and
        # SHOP_MANAGER stays the comma-less last row.
        self.assertIn("    ('ADMIN', 'zz:last:code'),\n", text)
        self.assertIn("    ('SHOP_MANAGER', 'catalog:product:view')\n", text)
        self.assertIn("        ('workorder:note:add'),\n        ('zz:last:code')\n", text)
        self.assertNotIn(",,", text)
        self.assertNotIn("),\n) AS", text)

    def test_rerunning_the_same_grant_changes_nothing(self) -> None:
        grants = {"catalog:tread_design:resolve": ["ADMIN"]}
        bits = {"catalog:tread_design:resolve": 42}
        self.sync(grants, bits)
        seed_after_first = self.seed_path.read_text(encoding="utf-8")
        csv_after_first = self.csv_path.read_text(encoding="utf-8")

        self.assertEqual(self.sync(grants, bits), [])
        self.assertEqual(self.seed_path.read_text(encoding="utf-8"), seed_after_first)
        self.assertEqual(self.csv_path.read_text(encoding="utf-8"), csv_after_first)

    def test_non_seed_role_grant_stays_out_of_the_sql_grant_and_self_check_blocks(self) -> None:
        # The seed may only grant to the roles Flyway still creates (#1613 D8), and section 4 is
        # pinned equal to section 3 by RolePermissionBaselineTest — so a SERVICE_ADVISOR grant is
        # the CSV's alone. The permission row still lands: role_permissions is FK'd to permissions.
        self.sync({"catalog:tread_design:resolve": ["SERVICE_ADVISOR"]}, {"catalog:tread_design:resolve": 42})

        self.assertIn("catalog:tread_design:resolve", self.seed_rows("SEED_PERMISSION_ROWS_MARKER", "_permission_row_key"))
        self.assertNotIn(
            ("SERVICE_ADVISOR", "catalog:tread_design:resolve"),
            self.seed_rows("SEED_GRANT_ROWS_MARKER", "_grant_row_key"),
        )
        self.assertNotIn("catalog:tread_design:resolve", self.seed_rows("SEED_SELF_CHECK_MARKER", "_self_check_row_key"))
        self.assertEqual(self.csv_row("SERVICE_ADVISOR"), ["catalog:product:view", "catalog:tread_design:resolve"])

    def test_dry_run_writes_nothing_but_still_reports(self) -> None:
        before_seed = self.seed_path.read_text(encoding="utf-8")
        before_csv = self.csv_path.read_text(encoding="utf-8")

        messages = self.sync({"catalog:tread_design:resolve": ["ADMIN"]}, {"catalog:tread_design:resolve": 42}, dry_run=True)

        self.assertTrue(messages)
        self.assertEqual(self.seed_path.read_text(encoding="utf-8"), before_seed)
        self.assertEqual(self.csv_path.read_text(encoding="utf-8"), before_csv)

    def test_unknown_role_is_refused_rather_than_invented(self) -> None:
        with self.assertRaises(ValueError) as ctx:
            self.generator.sync_role_permissions_csv(self.root, {"catalog:x:view": ["NO_SUCH_ROLE"]}, False)
        self.assertIn("NO_SUCH_ROLE", str(ctx.exception))
        self.assertEqual(self.csv_path.read_text(encoding="utf-8"), CSV_TEMPLATE)

    def test_missing_bit_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            self.generator.sync_seed_sql(self.root, {"catalog:x:view": ["ADMIN"]}, {}, False)

    def test_all_granted_permissions_reads_both_sources(self) -> None:
        self.csv_path.write_text(CSV_TEMPLATE + "TECHNICIAN,only:in:csv\n", encoding="utf-8")
        granted = self.generator.all_granted_permissions(self.root)
        self.assertIn("only:in:csv", granted)
        self.assertIn("workorder:note:add", granted)

    def test_grant_target_precedence_manifest_then_flag_then_admin(self) -> None:
        manifest = {"catalog:tread_design:resolve": ["SERVICE_ADVISOR"]}
        # The owning module's decision wins over a run-wide flag.
        self.assertEqual(
            self.generator.resolve_grant_roles("catalog:tread_design:resolve", manifest, ["MANAGER"]),
            ["SERVICE_ADVISOR"],
        )
        self.assertEqual(self.generator.resolve_grant_roles("catalog:other:view", manifest, ["MANAGER"]), ["MANAGER"])
        self.assertEqual(self.generator.resolve_grant_roles("catalog:other:view", manifest, []), ["ADMIN"])

    def test_system_administrator_grants_are_refused(self) -> None:
        # Its seed block is duplicated in V31 and pinned by RolePermissionBaselineTest; this
        # script edits only one of the two files, so it must not make the grant at all.
        with self.assertRaises(ValueError):
            self.generator.validate_grant_roles(self.root, ["SYSTEM_ADMINISTRATOR"])
        with self.assertRaises(ValueError):
            self.generator.resolve_grant_roles("catalog:x:view", {}, ["SYSTEM_ADMINISTRATOR"])

    def test_grant_to_is_preserved_when_a_manifest_is_regenerated(self) -> None:
        module = self.root / "pos-demo"
        yaml_path = module / "src" / "main" / "resources" / "permissions.yaml"
        yaml_path.parent.mkdir(parents=True, exist_ok=True)
        yaml_path.write_text(
            'domain: demo\nserviceName: pos-demo\nversion: "1.0"\npermissions:\n'
            '  - name: "demo:widget:view"\n    description: "View widget"\n'
            '    grantTo:\n      - "SERVICE_ADVISOR"\n',
            encoding="utf-8",
        )
        java = module / "src" / "main" / "java" / "Demo.java"
        java.parent.mkdir(parents=True, exist_ok=True)
        java.write_text(
            '@PreAuthorize("hasAuthority(\'demo:widget:view\')")\n'
            '@PreAuthorize("hasAuthority(\'demo:gadget:view\')")\n',
            encoding="utf-8",
        )

        self.generator.process_module(module, False, False, {})

        reloaded = yaml.safe_load(yaml_path.read_text(encoding="utf-8"))
        entries = {e["name"]: e for e in reloaded["permissions"]}
        self.assertEqual(entries["demo:widget:view"]["grantTo"], ["SERVICE_ADVISOR"])
        self.assertNotIn("grantTo", entries["demo:gadget:view"])
        self.assertEqual(
            self.generator.collect_manifest_grant_targets(self.root),
            {"demo:widget:view": ["SERVICE_ADVISOR"]},
        )


class SyncCheckGrantReportTest(unittest.TestCase):
    """#1848 review: --check must not claim a bit the catalog does not carry."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.generator = load_generator_module()

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)

    def _write(self, relative: str, text: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text), encoding="utf-8")
        return path

    def _write_root(self, catalog_entries: str, annotated: str) -> None:
        self._write(
            self.generator.PERMISSION_CODE_RELPATH,
            f"""\
            public enum PermissionCode {{
            {catalog_entries}

                public static final int CATALOG_VERSION = 2;
            }}
            """,
        )
        mirror_entries = ",\n".join(
            f'            "PERM_{name}"'
            for name in re.findall(r'\(\d+,\s*"([^"]+)"\)', catalog_entries)
        )
        for relative, class_name in (
            (self.generator.GATEWAY_CATALOG_RELPATH, "GatewayPermissionCatalog"),
            (self.generator.DOWNSTREAM_CATALOG_RELPATH, "DownstreamPermissionCatalog"),
        ):
            self._write(
                relative,
                f"""\
                public final class {class_name} {{
                    public static final int CATALOG_VERSION = 2;
                    static final String[] AUTHORITY_BY_BIT = {{
                {mirror_entries}
                    }};
                }}
                """,
            )
        # demo:view is the only granted permission in either source.
        self._write(
            self.generator.SEED_SQL_RELPATH,
            """\
            INSERT INTO permissions (
                id, name, description, domain, resource, action,
                registered_at, registered_by_service, version, bit_index)
            SELECT gen_random_uuid(), c.name, c.name, c.domain, c.resource, c.action,
                   NOW(), 'pos-security-service', '1.0', c.bit_index
            FROM (VALUES
                ('demo:view', 'demo', '', 'view', 0)
            ) AS c(name, domain, resource, action, bit_index)
            ON CONFLICT DO NOTHING;

            INSERT INTO role_permissions (role_id, permission_id)
            SELECT r.id, p.id
            FROM (VALUES
                ('ADMIN', 'demo:view')
            ) AS g(role_name, permission_name)
            JOIN roles r ON r.name = g.role_name
            JOIN permissions p ON p.name = g.permission_name
            ON CONFLICT DO NOTHING;

            DO $$
            BEGIN
                SELECT 1
                  FROM (VALUES
                    ('demo:view')
                  ) AS g(permission_name);
            END $$;
            """,
        )
        self._write(self.generator.ROLE_PERMISSIONS_CSV_RELPATH, "roleName,permissions\nADMIN,demo:view\n")
        self._write(
            "pos-demo/src/main/java/DemoController.java",
            f"""\
            @PreAuthorize("hasAnyAuthority({annotated})")
            class DemoController {{}}
            """,
        )

    def _run_check(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(MODULE_PATH), str(self.root), "--sync", "--check"],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_check_does_not_claim_a_bit_for_an_unregistered_permission(self) -> None:
        # demo:brandnew is annotated but absent from PermissionCode. --check writes nothing, so
        # the bit it would be given does not exist; reporting it as bit-indexed-but-ungranted
        # states something false and repeats the unregistered error under a second heading.
        self._write_root('    DEMO__VIEW(0, "demo:view");', "'demo:view', 'demo:brandnew'")

        result = self._run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("not registered in PermissionCode", result.stderr)
        self.assertIn("demo:brandnew", result.stderr)
        self.assertNotIn("have a PermissionCode bit", result.stderr)
        self.assertEqual(
            (self.root / self.generator.PERMISSION_CODE_RELPATH).read_text(encoding="utf-8").count("demo:brandnew"),
            0,
        )

    def test_check_still_reports_a_bit_indexed_permission_that_no_role_holds(self) -> None:
        # The narrowing must not disarm the gate: demo:edit does have a bit, is required by an
        # annotation, and is granted by neither source — the unreachable endpoint #1848 is about.
        self._write_root(
            '    DEMO__VIEW(0, "demo:view"),\n    DEMO__EDIT(1, "demo:edit");',
            "'demo:view', 'demo:edit'",
        )

        result = self._run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("have a PermissionCode bit", result.stderr)
        self.assertIn("demo:edit", result.stderr)
        self.assertNotIn("not registered in PermissionCode", result.stderr)

    def test_check_is_silent_when_every_annotated_bit_is_granted(self) -> None:
        self._write_root('    DEMO__VIEW(0, "demo:view");', "'demo:view'")

        result = self._run_check()

        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertNotIn("have a PermissionCode bit", result.stderr)


if __name__ == "__main__":
    unittest.main()
