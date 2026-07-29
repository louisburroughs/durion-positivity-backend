"""Corpus index — the authoritative "what docs exist and what do they cover" lookup.

The four-way classifier's hardest call is telling a **corpus gap** (#1, no doc on the topic exists)
from a **retrieval miss** (#2, a doc exists but was not retrieved). That call needs two things this
module provides:

1. an inventory of every doc in the corpus (id, rag_scope, required_permissions), and
2. a content lookup — "does *any* doc, retrieved or not, plausibly cover this topic?" — so an
   unknown gap (no SME-declared expected doc) can still be distinguished from a retrieval miss.

The manifest is a verbatim copy of ``scripts/rag_seed.py``'s ``MANIFEST`` (itself a copy of
``application-alpha.yml``'s ``mcp.rag.preload.docs``). ``CorpusTest.test_manifest_in_sync_with_rag_seed``
in ``scripts/tests/test_gap_harness.py`` asserts the two stay in sync, so a doc added to the corpus
fails the test until it is mirrored here.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parents[2]
RAG_DIR = ROOT / "pos-mcp-server/src/main/resources/rag"

# (doc_id, source_file, rag_scope, required_permissions). Keep in sync with rag_seed.py MANIFEST.
MANIFEST: list[tuple[str, str, str, list[str]]] = [
    ("accounting.de-bookkeeping", "de-bookkeeping-rag.md", "accounting", []),
    ("accounting.journal-entries", "accounting-journal-entries-rag.md", "accounting", ["accounting:je:view"]),
    ("accounting.financial-statements", "accounting-financial-statements-rag.md", "accounting", ["reporting:view:financial-statements"]),
    ("accounting.report-export", "accounting-report-export-rag.md", "accounting", ["accounting:report:export"]),
    ("accounting.codes", "accounting-codes-rag.md", "accounting", ["accounting:je:view"]),
    ("inventory.inv-cntrl", "inv-cntrl-rag.md", "inventory", []),
    ("inventory.purchase-orders", "inventory-purchase-orders-rag.md", "inventory", ["inventory:purchase_order:view"]),
    ("inventory.receiving", "inventory-receiving-rag.md", "inventory", ["inventory:receiving:view"]),
    ("inventory.transfers-adjustments", "inventory-transfers-adjustments-rag.md", "inventory", ["inventory:transfer:view"]),
    ("inventory.codes", "inventory-codes-rag.md", "inventory", ["inventory:purchase_order:view"]),
    ("people.human-resources", "hr-functions-guide.md", "hr", []),
    ("shop.management", "shop-management-rag.md", "shopmanager", []),
    ("shop.management.guidelines", "shop-management-guidelines.md", "shopmanager", []),
    ("security.guide", "security-service-guide.md", "master", []),
    ("platform.capability-catalog", "capability-catalog.md", "master", ["AUTHENTICATED"]),
    ("workflow.cross-domain-playbooks", "cross-domain-playbooks.md", "master", ["AUTHENTICATED"]),
    ("glossary.identifiers", "glossary-identifiers.md", "master", ["AUTHENTICATED"]),
    ("order.guide", "order-guide.md", "order", ["order:order:view"]),
    ("order.lifecycle", "order-lifecycle-rag.md", "order", ["order:order:view"]),
    ("order.price-override", "order-price-override-rag.md", "order", ["order:price_override:view"]),
    ("order.codes", "order-codes-rag.md", "order", ["order:order:view"]),
    ("order.returns-refunds", "returns-refunds-rag.md", "order", ["order:order:view"]),
    ("pricing.guide", "pricing-guide.md", "pricing", ["pricing:price_book:view", "pricing:rule:view"]),
    ("pricing.promotions", "pricing-promotions-rag.md", "pricing", ["pricing:promotion:view"]),
    ("pricing.restrictions", "pricing-restrictions-rag.md", "pricing", ["AUTHENTICATED"]),
    ("pricing.codes", "pricing-codes-rag.md", "pricing", ["pricing:promotion:view"]),
    ("warranty.guide", "warranty-guide.md", "warranty", ["warranty:claim:view"]),
    ("tax.guide", "tax-guide.md", "tax", ["tax:mode:view"]),
    (
        "crm.customer-vehicle",
        "customer-vehicle-guide.md",
        "customer",
        ["crm:party:view", "crm:party:search", "crm:vehicle:view", "crm:vehicle:search", "crm:contact:view"],
    ),
    ("crm.party-account-model", "crm-party-account-model-rag.md", "customer", ["crm:party:view"]),
    ("crm.codes", "crm-codes-rag.md", "customer", ["crm:party:view"]),
    ("reporting.metrics", "reporting-metrics.md", "reporting", ["accounting:report:export", "workorder:dashboard:view"]),
    ("admin.governance", "admin-governance.md", "admin", ["security:permission:view", "workorder:approval_config:view"]),
    ("events.observability", "events-observability.md", "events", ["nlti:audit:read"]),
    ("security.role-permission-matrix", "role-permission-matrix.md", "security", ["security:role:view", "security:permission:view"]),
    ("workorder.estimate-promotion", "workorder-estimate-promotion-rag.md", "workorder", ["workorder:estimate:view"]),
    ("workorder.status-lifecycle", "workorder-status-lifecycle-rag.md", "workorder", ["workorder:workorder:view"]),
    ("workorder.approval-config", "workorder-approval-config-rag.md", "workorder", ["workorder:approval_config:view"]),
    ("workorder.codes", "workorder-codes-rag.md", "workorder", ["workorder:estimate:view"]),
]

# The pseudo-permission every authenticated caller holds (mirrors PermissionAwareMetadataFilter and
# eval_live.rag_visible_docs). A doc requiring only AUTHENTICATED is effectively public.
AUTHENTICATED = "AUTHENTICATED"

_TOKEN = re.compile(r"[a-z0-9]+")
# Words too common in this domain corpus to carry topic signal for the coverage lookup.
_STOP = frozenset(
    """a an the of to in on at for and or is are be as by with from into onto over under out up off
    about above below after before between that this these those which what who how when where why
    can does do did i you it its their our we they them he she will would should could may might if
    then else so no not any all use used using per via my your me get got need needs
    v1 rag scope id required permissions verified""".split()
)


def _tokens(text: str) -> list[str]:
    return [t for t in _TOKEN.findall(text.lower()) if t not in _STOP and len(t) > 1]


@dataclass
class Doc:
    doc_id: str
    source_file: str
    scope: str
    required_permissions: frozenset[str]
    content: str = ""
    verified_facts: tuple[str, ...] = ()
    _tf: dict[str, int] = field(default_factory=dict, repr=False)

    def visible_to(self, actor_permission_codes: set[str]) -> bool:
        """PermissionAwareMetadataFilter semantics: a doc with no required perms (or only
        AUTHENTICATED) is visible to any authenticated caller; otherwise the caller must hold at
        least one of the required codes."""
        req = set(self.required_permissions) - {AUTHENTICATED}
        if not req:
            return True
        caller = set(actor_permission_codes) | {AUTHENTICATED}
        return bool(req & caller)


class CorpusIndex:
    """In-memory index over the preloaded RAG corpus. Pure/offline: reads only the checked-in
    markdown, never the DB."""

    def __init__(self, docs: list[Doc]):
        self.docs: dict[str, Doc] = {d.doc_id: d for d in docs}
        # Document frequency for idf weighting of the coverage lookup.
        self._df: dict[str, int] = {}
        for d in docs:
            for term in set(d._tf):
                self._df[term] = self._df.get(term, 0) + 1
        self._n = max(1, len(docs))

    # --- inventory -----------------------------------------------------------------------------
    def exists(self, doc_id: str) -> bool:
        return doc_id in self.docs

    def get(self, doc_id: str) -> Optional[Doc]:
        return self.docs.get(doc_id)

    def all_doc_ids(self) -> list[str]:
        return sorted(self.docs)

    # --- coverage lookup -----------------------------------------------------------------------
    def score(self, query: str) -> list[tuple[str, float]]:
        """idf-weighted token-overlap score of the query against every doc, high to low. A cheap,
        deterministic stand-in for "does any doc cover this topic?" — it is not the production dense
        retriever (that is captured separately, from the DB); it only needs to answer the corpus
        *existence* question, which content overlap does well and without embeddings."""
        return [(doc_id, s) for doc_id, s, _ratio in self._scored(query)]

    def _scored(self, query: str) -> list[tuple[str, float, float]]:
        """(doc_id, idf-score, distinctive-term-coverage-ratio), high score to low."""
        q_terms = set(_tokens(query))
        if not q_terms:
            return []
        scored = []
        for doc_id, d in self.docs.items():
            s = 0.0
            matched = 0
            for term in q_terms:
                tf = d._tf.get(term, 0)
                if tf:
                    matched += 1
                    idf = math.log(1 + self._n / (1 + self._df.get(term, 0)))
                    s += (1 + math.log(tf)) * idf
            if s > 0:
                scored.append((doc_id, s, matched / len(q_terms)))
        scored.sort(key=lambda x: (-x[1], x[0]))
        return scored

    def covering_docs(
        self, query: str, threshold: float = 3.0, min_term_coverage: float = 0.4, top_n: int = 3
    ) -> list[str]:
        """Doc ids whose content plausibly covers the query's topic. A doc must both clear the
        idf-score ``threshold`` AND match at least ``min_term_coverage`` of the query's distinctive
        terms — the ratio gate stops a doc that merely shares one or two rare words (e.g. "points",
        "checkout") from being mistaken for topic coverage, which would mis-file a real corpus gap
        as a retrieval miss. Empty list is the signal for a candidate corpus gap."""
        return [
            doc_id
            for doc_id, s, ratio in self._scored(query)[:top_n]
            if s >= threshold and ratio >= min_term_coverage
        ]


def _parse_verified_facts(content: str) -> tuple[str, ...]:
    """Extract the ``_Verified: …_`` citations (§4 grounding source #2). These are the module
    source-of-truth facts the glossary/domain docs commit to, and the strongest ground truth the
    judge can be handed when a question has no SME expected_fact."""
    facts = []
    for m in re.finditer(r"_Verified:\s*(.+?)_\s*$", content, flags=re.MULTILINE | re.DOTALL):
        facts.append(re.sub(r"\s+", " ", m.group(1)).strip())
    return tuple(facts)


def load_corpus(rag_dir: Path = RAG_DIR) -> CorpusIndex:
    docs = []
    for doc_id, source_file, scope, perms in MANIFEST:
        path = rag_dir / source_file
        content = path.read_text() if path.is_file() else ""
        tf: dict[str, int] = {}
        for tok in _tokens(content):
            tf[tok] = tf.get(tok, 0) + 1
        docs.append(
            Doc(
                doc_id=doc_id,
                source_file=source_file,
                scope=scope,
                required_permissions=frozenset(perms),
                content=content,
                verified_facts=_parse_verified_facts(content),
                _tf=tf,
            )
        )
    return CorpusIndex(docs)
