#!/usr/bin/env python3
"""Seed the RAG corpus into pgvector directly from Python — no JVM, no redeploy, no auth token.
Faithfully mirrors the production ingest path (DocumentEmbeddingIngestor + StaticRagPreloadServiceImpl)
so a Python-only host can populate the same corpus that `application-alpha.yml`'s preload would
produce on startup, letting `eval_live.py` measure the recall@k baseline BEFORE the config change is
merged and deployed.

Faithful to production:
  * same chunking: max-segment 2000, overlap 200, step 1800, trim, drop-empty (matches
    DocumentEmbeddingIngestor.split with default mcp.rag.chunking.* values);
  * same metadata keys: document_id, source_path, rag_scope, required_permissions (comma-joined,
    only when non-empty), chunk_index, chunk_count;
  * same rag_scope normalization (lowercase/trim, blank -> master).
The 17-doc manifest below is a verbatim copy of application-alpha.yml's mcp.rag.preload.docs — keep
them in sync. This is an eval seeding aid, not a production ingestor.

Idempotent: deletes existing preloaded rows (source_path LIKE 'classpath:rag/%') before inserting, so
re-running gives a clean corpus. Leaves chat-memory rows (no classpath:rag source_path) untouched.

Usage:
    ENV_FILE=/opt/durion/alpha/.env OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434 \
        python3 scripts/rag_seed.py
"""
import json
import sys

import eval_live as E  # reuse DB_* config, .env resolution, embed(), vec_literal()

RAG_DIR = E.ROOT / "pos-mcp-server/src/main/resources/rag"
MAX_SEGMENT = 2000
OVERLAP = 200
STEP = MAX_SEGMENT - OVERLAP  # 1800

# Verbatim from application-alpha.yml mcp.rag.preload.docs (id, source file, rag-scope, required perms).
MANIFEST = [
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
    ("crm.customer-vehicle", "customer-vehicle-guide.md", "customer",
     ["crm:party:view", "crm:party:search", "crm:vehicle:view", "crm:vehicle:search", "crm:contact:view"]),
    ("crm.party-account-model", "crm-party-account-model-rag.md", "customer", ["crm:party:view"]),
    ("crm.codes", "crm-codes-rag.md", "customer", ["crm:party:view"]),
    ("reporting.metrics", "reporting-metrics.md", "reporting",
     ["accounting:report:export", "workorder:dashboard:view"]),
    ("admin.governance", "admin-governance.md", "admin",
     ["security:permission:view", "workorder:approval_config:view"]),
    ("events.observability", "events-observability.md", "events", ["nlti:audit:read"]),
    ("security.role-permission-matrix", "role-permission-matrix.md", "security",
     ["security:role:view", "security:permission:view"]),
    ("workorder.estimate-promotion", "workorder-estimate-promotion-rag.md", "workorder", ["workorder:estimate:view"]),
    ("workorder.status-lifecycle", "workorder-status-lifecycle-rag.md", "workorder", ["workorder:workorder:view"]),
    ("workorder.approval-config", "workorder-approval-config-rag.md", "workorder", ["workorder:approval_config:view"]),
    ("workorder.codes", "workorder-codes-rag.md", "workorder", ["workorder:estimate:view"]),
]


def normalize_scope(scope):
    return (scope or "").strip().lower() or "master"


def split(content):
    """Replicate DocumentEmbeddingIngestor.split (sliding window, trim, drop-empty)."""
    segments = []
    start, length = 0, len(content)
    while start < length:
        end = min(length, start + MAX_SEGMENT)
        segment = content[start:end].strip()
        if segment:
            segments.append(segment)
        if end >= length:
            break
        start += STEP
    return segments or [content]


def main():
    try:
        import pg8000.native
    except ImportError:
        sys.exit("Missing driver. Run:  pip install --user pg8000")
    if not E.DB_USER or not E.DB_PASS:
        sys.exit("Could not resolve DB user/password (env or .env). Set ENV_FILE=/opt/durion/alpha/.env")

    con = pg8000.native.Connection(
        user=E.DB_USER, password=E.DB_PASS, host=E.DB_HOST, port=E.DB_PORT, database=E.DB_NAME
    )
    print(f"DB={E.DB_USER}@{E.DB_HOST}:{E.DB_PORT}/{E.DB_NAME}  embed={E.OLLAMA} ({E.MODEL})\n")

    removed = con.run(
        "DELETE FROM mcp_document_embedding WHERE metadata->>'source_path' LIKE 'classpath:rag/%' RETURNING id"
    )
    print(f"cleared {len(removed)} existing preloaded RAG rows\n")

    total_chunks = 0
    for doc_id, filename, raw_scope, perms in MANIFEST:
        path = RAG_DIR / filename
        if not path.is_file():
            print(f"  SKIP {doc_id}: missing {path}")
            continue
        content = path.read_text(encoding="utf-8")
        scope = normalize_scope(raw_scope)
        chunks = split(content)
        for index, chunk in enumerate(chunks):
            metadata = {
                "document_id": doc_id,
                "source_path": f"classpath:rag/{filename}",
                "rag_scope": scope,
                "chunk_index": index,
                "chunk_count": len(chunks),
            }
            if perms:
                metadata["required_permissions"] = ",".join(perms)
            con.run(
                "INSERT INTO mcp_document_embedding (content, metadata, embedding) "
                "VALUES (:c, CAST(:m AS jsonb), CAST(:e AS vector))",
                c=chunk, m=json.dumps(metadata), e=E.vec_literal(E.embed(chunk)),
            )
        total_chunks += len(chunks)
        print(f"  {doc_id:34} scope={scope:11} perms={len(perms)} chunks={len(chunks)}")

    print(f"\nseeded {len(MANIFEST)} docs, {total_chunks} chunks")
    con.close()


if __name__ == "__main__":
    main()
