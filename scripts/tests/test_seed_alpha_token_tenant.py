"""Pins on seed-alpha.py's JWT `tid` parser (ADR-0062, plan WS8).

The driver decides which tenant it loads into from the access token's `tid` claim, and it
reads that claim without verifying the signature -- the gateway does the verifying, this only
picks a default. That means the claim is arbitrary attacker-influenced JSON as far as this
function is concerned, and a claim that is not a UUID string must come back as "no tenant",
never as a traceback that aborts the run before the first pack loads.

Stdlib only: no build, no network, no gateway.
"""

import base64
import importlib.util
import json
import pathlib
import unittest

_DRIVER_PATH = pathlib.Path(__file__).resolve().parents[1] / "seed-alpha.py"


def _load_driver():
    """Import seed-alpha.py under a module name of its own (the hyphen bars a plain import)."""
    spec = importlib.util.spec_from_file_location("seed_alpha", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


def _token(claims):
    """An unsigned JWT-shaped string carrying `claims`; only the payload segment is read."""
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode("utf-8")).decode("ascii").rstrip("=")
    return "header." + payload + ".signature"


class TokenTenantIdTest(unittest.TestCase):
    def test_uuid_claim_is_returned_canonicalised(self):
        self.assertEqual(
            "01900000-0000-7000-8000-000000000001",
            seed_alpha.token_tenant_id(_token({"tid": "01900000-0000-7000-8000-000000000001"})),
        )

    def test_missing_claim_is_no_tenant(self):
        self.assertIsNone(seed_alpha.token_tenant_id(_token({"sub": "admin.alpha"})))

    def test_non_uuid_string_claim_is_no_tenant(self):
        self.assertIsNone(seed_alpha.token_tenant_id(_token({"tid": "not-a-uuid"})))

    def test_non_string_claims_are_no_tenant_rather_than_a_crash(self):
        # A number reaches uuid.UUID() as an int (TypeError) and a list as a list
        # (AttributeError: no .replace). Catching only ValueError let either abort the driver.
        for claim in (5, 3.5, ["01900000-0000-7000-8000-000000000001"], {"id": "x"}, True):
            with self.subTest(claim=claim):
                self.assertIsNone(seed_alpha.token_tenant_id(_token({"tid": claim})))

    def test_claims_that_are_not_an_object_are_no_tenant(self):
        self.assertIsNone(seed_alpha.token_tenant_id(_token(["tid"])))

    def test_a_token_without_a_payload_segment_is_no_tenant(self):
        self.assertIsNone(seed_alpha.token_tenant_id("not-a-jwt"))

    def test_an_undecodable_payload_is_no_tenant(self):
        self.assertIsNone(seed_alpha.token_tenant_id("header.!!!not-base64!!!.signature"))


if __name__ == "__main__":
    unittest.main()
