# Backlog — deferred items surfaced during NL-interface work

Items noted but intentionally not being fixed yet.

## BL-1 — Warranty / claim capability (platform / cross-domain) — RESOLVED
**Status:** resolved (2026-07). Implemented as the `pos-warranty` module per `docs/PRD-warranty-claims-module.md` (owning service, `WC-{yyyy}-{seq}` claim-code schema, claim state machine, customer-first settlement, reimbursement + part-return workflows). The two RAG docs below have been updated (OPEN notes removed) and `warranty:*` permissions registered.

**Finding (verified 2026-06-30):** a repo-wide search found **no warranty-claim or RMA workflow in any service**. "Warranty" exists only as a descriptive product attribute in the catalog (`pos-catalog` `ProductEntity.warranty` / `manufacturerWarranty`, e.g. "1 year limited warranty"). Inventory owns customer *returns* (`inventory:return:*`) and invoice owns payments/refunds — neither is a warranty claim. The only `claim` permission is `inventory:putaway:claim` (claiming a putaway task, unrelated). There is no claim-code format, claim state machine, or reimbursement workflow.

**Why it's a backlog item, not a code gap:** whether a warranty/claim capability *should* exist is a product decision; nothing in code today owns it, so it cannot be authored from the repo.

**Where it surfaced:** Gate 5 RAG docs flag this inline:
- `src/main/resources/rag/glossary-identifiers.md` (Claim code)
- `src/main/resources/rag/cross-domain-playbooks.md` (warranty/claim playbook)

**When picked up:** decide owning service + claim-code schema + claim state machine + reimbursement workflow, then update the two RAG docs above (remove the OPEN notes) and add `required-permissions` for any new claim domain. *(Done — see Status above; the historical finding is kept for context.)*
