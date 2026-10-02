"""Validates the ADR-0068 tagging gate fixtures (spec 2.9): schema, every tag present, values in the
allowed sets, unique ids, and the per-language minimum counts. Stdlib only, no build, no network.

    python3 -m unittest scripts.test_tagging_gate_fixtures

The allowed values below are the schema. The entity keys and the domain options are checked against
their sources (entities.yaml, application.yml) so the fixtures cannot drift from the lexicon.
"""

import collections
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODULE = ROOT / "pos-mcp-server"
GATE_DIR = MODULE / "src" / "test" / "resources" / "eval" / "tagging-gate"
ENTITIES_YAML = MODULE / "src" / "main" / "resources" / "scope-graph" / "entities.yaml"
APPLICATION_YML = MODULE / "src" / "main" / "resources" / "application.yml"

LANGUAGES = {"en": True, "fr-CA": False, "es": False}  # language -> reviewed

SCHEMA_VERSION = 1
NOUL_TAGS = (
    "follows_previous_turn",
    "simple_chat",
    "needs_web_search",
    "about_inventory",
    "about_orders",
    "implies_date_window",
    "admin_account_question",
    "compound_question",
)
CHOICE_OPTIONS = {
    "workflow_state": ("IDLE", "CREATING_PO", "RECEIVING_ASN", "INVENTORY_RECON", "PROCESSING_RETURN"),
    "intent": ("QUERY", "ACTION", "UNKNOWN"),
    "complexity": ("SINGLE_LOOKUP", "MULTI_DOMAIN"),
    "risk": ("LOW", "MEDIUM", "HIGH"),
    "domain": (
        "accounting", "admin", "customer", "events", "hr", "inventory", "master", "order", "pricing",
        "reporting", "security", "shopmanager", "tax", "warranty", "workorder",
    ),
}
ALL_TAGS = NOUL_TAGS + tuple(CHOICE_OPTIONS) + ("entity",)

# Entity key -> the tool-catalog domain of its lexicon entry (entities.yaml `domain:`).
ENTITY_DOMAIN = {
    "workorder": "workorder", "estimate": "workorder", "appointment": "shop-manager", "customer": "customer",
    "vehicle": "vehicle-inventory", "invoice": "invoice", "payment": "invoice", "order": "order",
    "return": "order", "purchase-order": "order", "asn": "inventory", "stock-item": "inventory",
    "stock-transfer": "inventory", "stock-adjustment": "inventory", "product": "catalog", "price": "price",
    "promotion": "price", "tax": "tax", "location": "location", "supplier": "supplier",
    "warranty-claim": "warranty", "employee": "people", "user": "security-service",
    "role": "security-service", "journal-entry": "accounting", "gl-account": "accounting",
    "financial-report": "accounting", "bank-reconciliation": "accounting", "vendor-bill": "accounting",
    "credit-memo": "accounting", "sales-report": "accounting", "campaign": "marketing",
}
ENTITY_KEYS = tuple(ENTITY_DOMAIN)

SOURCE_PATTERN = re.compile(r"^(new|[\w.\-/]+#[\w.\-]+)( \(translated\))?$")
ID_PATTERN = r"^%s-\d{4}$"

MIN_POSITIVE, MIN_NEGATIVE, MIN_HARD_NEGATIVE = 15, 15, 5
MIN_PER_OPTION = 5
MIN_TOTAL = {"workflow_state": 25, "intent": 15, "complexity": 10, "risk": 15, "domain": 75}
MIN_IDLE_WITH_WORKFLOW_VOCABULARY = 10
MIN_PER_ENTITY = 3
MIN_SAME_DOMAIN_PAIRS = 10

# IDLE utterances that use purchase order, ASN / receiving or return vocabulary (en, fr-CA, es).
WORKFLOW_VOCABULARY = re.compile(
    r"purchase order|\bPO\b|\bASN\b|shipment|goods receipt|cycle count|reconcil|return|refund"
    r"|bon de commande|purchase orders|livraison|décompte|réconcil|retour|rembours"
    r"|orden de compra|envío|entrega|conteo|concili|devoluci|reembols",
    re.IGNORECASE,
)


def load(language):
    with open(GATE_DIR / f"{language}.json", encoding="utf-8") as handle:
        return json.load(handle)


def is_negative(tag, value):
    return value is False or value == "IDLE"


class GateFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = {language: load(language) for language in LANGUAGES}

    def test_sources_stay_in_sync(self):
        # The sources are part of this repo: a missing or moved file is a failure, not a skip,
        # or the drift check would silently stop running.
        self.assertTrue(ENTITIES_YAML.is_file(), f"{ENTITIES_YAML} not found")
        self.assertTrue(APPLICATION_YML.is_file(), f"{APPLICATION_YML} not found")
        text = ENTITIES_YAML.read_text(encoding="utf-8")
        entities = re.findall(r"^  - key: ([a-z0-9-]+)\n    domain: ([a-z-]+)", text, re.MULTILINE)
        self.assertEqual(dict(entities), ENTITY_DOMAIN, "entity keys/domains drifted from entities.yaml")
        # Hyphens allowed: a rag-scope such as "shop-manager" must show up as drift, not vanish
        # from the match.
        scopes = set(re.findall(r'rag-scope:\s*"?([a-z][a-z0-9-]*)"?', APPLICATION_YML.read_text(encoding="utf-8")))
        self.assertTrue(scopes, "no rag-scope values found in application.yml")
        self.assertEqual(scopes | {"master"}, set(CHOICE_OPTIONS["domain"]))

    def test_schema(self):
        for language, reviewed in LANGUAGES.items():
            with self.subTest(language=language):
                fixture = self.fixtures[language]
                self.assertEqual(set(fixture), {"schemaVersion", "language", "reviewed", "reviewer", "utterances"})
                self.assertEqual(fixture["schemaVersion"], SCHEMA_VERSION)
                self.assertEqual(fixture["language"], language)
                self.assertIs(fixture["reviewed"], reviewed)
                if not reviewed:
                    self.assertIsNone(fixture["reviewer"])
                ids, texts = set(), set()
                for utterance in fixture["utterances"]:
                    self.assertEqual(
                        set(utterance), {"id", "text", "source", "expected_tags", "hard_negative_for", "notes"})
                    self.assertRegex(utterance["id"], ID_PATTERN % re.escape(language))
                    self.assertNotIn(utterance["id"], ids)
                    ids.add(utterance["id"])
                    self.assertTrue(utterance["text"].strip())
                    self.assertNotIn(utterance["text"], texts, "duplicate text")
                    texts.add(utterance["text"])
                    self.assertRegex(utterance["source"], SOURCE_PATTERN)
                    self.assertIsInstance(utterance["notes"], str)

    def test_every_tag_present_with_allowed_values(self):
        for language in LANGUAGES:
            for utterance in self.fixtures[language]["utterances"]:
                tags = utterance["expected_tags"]
                where = f"{utterance['id']}: {utterance['text']}"
                self.assertEqual(set(tags), set(ALL_TAGS), where)
                for tag in NOUL_TAGS:
                    self.assertIsInstance(tags[tag], bool, f"{where} {tag}")
                for tag, options in CHOICE_OPTIONS.items():
                    self.assertIn(tags[tag], options, f"{where} {tag}")
                self.assertIsInstance(tags["entity"], list, where)
                self.assertEqual(len(tags["entity"]), len(set(tags["entity"])), where)
                for key in tags["entity"]:
                    self.assertIn(key, ENTITY_KEYS, f"{where} entity {key}")
                hard = utterance["hard_negative_for"]
                self.assertIsInstance(hard, list, where)
                for tag in hard:
                    self.assertIn(tag, NOUL_TAGS + ("workflow_state",), where)
                    self.assertTrue(is_negative(tag, tags[tag]), f"{where}: hard negative for {tag} is not a negative")

    def test_noul_minimums(self):
        for language in LANGUAGES:
            utterances = self.fixtures[language]["utterances"]
            for tag in NOUL_TAGS:
                with self.subTest(language=language, tag=tag):
                    positive = sum(1 for u in utterances if u["expected_tags"][tag])
                    negative = len(utterances) - positive
                    hard = sum(1 for u in utterances if tag in u["hard_negative_for"])
                    self.assertGreaterEqual(positive, MIN_POSITIVE)
                    self.assertGreaterEqual(negative, MIN_NEGATIVE)
                    self.assertGreaterEqual(hard, MIN_HARD_NEGATIVE)

    def test_choice_and_score_minimums(self):
        for language in LANGUAGES:
            utterances = self.fixtures[language]["utterances"]
            for tag, options in CHOICE_OPTIONS.items():
                with self.subTest(language=language, tag=tag):
                    counts = collections.Counter(u["expected_tags"][tag] for u in utterances)
                    for option in options:
                        self.assertGreaterEqual(counts[option], MIN_PER_OPTION, f"{tag}={option}")
                    self.assertGreaterEqual(sum(counts.values()), MIN_TOTAL[tag])
            with self.subTest(language=language, tag="workflow_state IDLE vocabulary"):
                idle = [u for u in utterances
                        if u["expected_tags"]["workflow_state"] == "IDLE" and WORKFLOW_VOCABULARY.search(u["text"])]
                self.assertGreaterEqual(len(idle), MIN_IDLE_WITH_WORKFLOW_VOCABULARY)
            with self.subTest(language=language, tag="workflow_state hard negatives"):
                hard = sum(1 for u in utterances if "workflow_state" in u["hard_negative_for"])
                self.assertGreaterEqual(hard, MIN_HARD_NEGATIVE)

    def test_entity_minimums(self):
        for language in LANGUAGES:
            utterances = self.fixtures[language]["utterances"]
            with self.subTest(language=language, check="per entity"):
                counts = collections.Counter(k for u in utterances for k in u["expected_tags"]["entity"])
                for key in ENTITY_KEYS:
                    self.assertGreaterEqual(counts[key], MIN_PER_ENTITY, key)
            with self.subTest(language=language, check="two-entity pairs"):
                pairs = [u for u in utterances
                         if len(u["expected_tags"]["entity"]) == 2
                         and len({ENTITY_DOMAIN[k] for k in u["expected_tags"]["entity"]}) == 1]
                self.assertGreaterEqual(len(pairs), MIN_SAME_DOMAIN_PAIRS)
                named = {frozenset(p) for p in (
                    ("invoice", "payment"), ("order", "return"), ("workorder", "estimate"),
                    ("journal-entry", "gl-account"), ("stock-item", "stock-transfer"))}
                found = {frozenset(u["expected_tags"]["entity"]) for u in pairs}
                self.assertTrue(named <= found, f"missing pairs: {sorted(map(sorted, named - found))}")


if __name__ == "__main__":
    unittest.main()
