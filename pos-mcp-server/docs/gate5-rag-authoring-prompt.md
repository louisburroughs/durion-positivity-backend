# Agent Prompt — Author Gate 5 RAG Documents (pos-mcp-server)

Hand this entire file to an authoring agent. It is self-contained.

---

## Role & mission
You are a technical writer + domain analyst for the **Durion Positivity** tire/service ERP. Author a
set of **RAG knowledge documents** for `pos-mcp-server`'s natural-language assistant. These documents
are retrieved at query time to ground the assistant's answers for **internal staff and platform
admins** (NOT end customers). Write for retrieval and grounding, not marketing.

Authoritative spec for this task: `pos-mcp-server/docs/gate5-rag-hybrid-design.md` (§G5.5 + the doc
table). Read it first. Also read these existing RAG docs and **match their tone, depth, and
structure** (do not duplicate their content):
`src/main/resources/rag/de-bookkeeping-rag.md`, `inv-cntrl-rag.md`, `shop-management-rag.md`,
`shop-management-guidelines.md`, `hr-functions-guide.md`, `security-service-guide.md`.

## Where files go & how they register
- One markdown file per document under `pos-mcp-server/src/main/resources/rag/`.
- Register each in `pos-mcp-server/src/main/resources/application.yml` under `mcp.rag.preload.docs`
  with: `id` (deterministic, dotted), `source-path` (`classpath:rag/<file>.md`), `rag-scope`, and a
  new `required-permissions` list (see the design's G5.2 — the schema field is being added; emit the
  YAML so it is ready). Provide the YAML block as part of your deliverable.

## Documents to write (exactly these; ids are deterministic — do not change)
| id | file | rag-scope | required_permissions | chunk target | audience |
|---|---|---|---|---|---|
| `platform.capability-catalog` | `capability-catalog.md` | master | `["AUTHENTICATED"]` | ~500 | all staff (per-role sections) |
| `workflow.cross-domain-playbooks` | `cross-domain-playbooks.md` | master | `["AUTHENTICATED"]` | 1500/200 | staff |
| `glossary.identifiers` | `glossary-identifiers.md` | master | `["AUTHENTICATED"]` | ~500 (small) | all |
| `order.guide` | `order-guide.md` | order | order read codes | 1500/200 | staff |
| `pricing.guide` | `pricing-guide.md` | pricing | pricing read codes | 1500/200 | staff |
| `tax.guide` | `tax-guide.md` | tax | tax read codes | 1500/200 | staff/accounting |
| `crm.customer-vehicle` | `customer-vehicle-guide.md` | customer | crm read codes | 1500/200 | staff |
| `reporting.metrics` | `reporting-metrics.md` | reporting | reporting read | 1500/200 | manager |
| `admin.governance` | `admin-governance.md` | admin | **admin perm** | 1500/200 | admin only |
| `events.observability` | `events-observability.md` | events | audit/observability perm | 1500/200 | admin/manager |
| `security.role-permission-matrix` | `role-permission-matrix.md` | security | **admin/security perm** | ~800 | admin/security |

Per-document content requirements:
- **capability-catalog** — per role (service advisor, technician, dispatcher, location manager,
  account manager, accounting associate, admin), a list of example questions/requests the assistant
  can handle in plain language + the kind of answer to expect. This is what makes the assistant feel
  intuitive; phrase examples the way staff actually speak.
- **cross-domain-playbooks** — the multi-step flows that cross modules: estimate → approval →
  workorder → parts/labor → invoice → payment; PO → receive → reconcile; warranty/claim. Show the
  hand-offs and what each step depends on.
- **glossary-identifiers** — terms, abbreviations (WO = workorder, etc.), and the **format of each
  identifier** (workorder #, SKU, VIN, invoice #, PO #, account/claim codes). Small, one-term-per-chunk.
- **order/pricing/tax/customer-vehicle/reporting** — domain concepts, key entities, the questions
  staff ask, and how to interpret answers. Define reporting metrics precisely.
- **admin-governance** — what needs approval, audit implications, blast-radius framing.
- **events-observability** — event types, how to reconstruct "what happened to entity X".
- **role-permission-matrix** — the concrete role→permission mapping (who can do/approve what).

## Hard constraints (do not violate)
1. **Never invent permission codes.** Use only codes that exist in the repo's `permissions.yaml`
   files (one per service: `pos-customer`, `pos-workorder`, `pos-invoice`, `pos-order`,
   `pos-inventory`, `pos-accounting`, `pos-price`, `pos-shop-manager`, `pos-mcp-server`, …). Verified
   samples (re-verify against the files; format is `domain:resource:action`):
   - CRM/customer/vehicle: `crm:party:view`, `crm:party:search`, `crm:vehicle:view`, `crm:vehicle:search`, `crm:contact:view`
   - workorder: `workorder:workorder:view`, `workorder:workorder:create`, `workorder:estimate:create`, `workorder:change_request:create`
   - invoice (coarse): `invoice:manage`, `invoice:finalize` (no `invoice:read`)
   - order: `order:order:view`, `order:order:create`, `order:line:create`
   - inventory: `inventory:on_hand:view`, `inventory:purchase_order:receive`, `inventory:goods_receipt:create`
   - accounting: `accounting:je:view`, `accounting:je:post`, `accounting:ap:view`
   - pricing: `pricing:price_book:view`, `pricing:rule:view`
   - shop-manager: `appointments:view`, `appointments:cancel`, `appointments:reschedule`, `shop:schedule:view`
   - mcp/nlti: `mcp:chat:execute`, `nlti:request:submit`, `nlti:audit:read`
   For each doc's `required_permissions`, pick real read-level codes for that domain; for admin/security
   docs use real admin/security codes. **If a needed code does not exist, say so — do not fabricate.**
2. **Never invent tool names or API behavior.** Discovered tool names follow
   `sanitize(firstNonVersionPathSegment + "_" + operationId)` lowercased (e.g. `/v1/crm/{id}`
   `getCustomerById` → `crm_getcustomerbyid`). If you reference a tool/operation, verify it against the
   service `openapi.yaml`. Prefer describing capabilities over hard-coding tool names.
3. **Ground every business rule** in an existing source (module `README.md`, `docs/adr/`, the existing
   RAG docs, or `permissions.yaml`). Mark anything you cannot verify with `> TODO(verify): …` rather
   than guessing.
4. **Permission disclaimer in every doc:** include one line stating the document is reference context
   only and grants no access — access is enforced by permission codes at request time.
5. **Naming:** `workorder` is one word everywhere.
6. **Audience:** internal staff + admins only. Do not write customer-facing content.

## Format & metadata (Retrieval lock — every doc must satisfy)
- Markdown, with a short purpose section at top stating `rag-scope` and `required_permissions`.
- Sectioned with clear `##` headings; identifier-rich; terminology precise.
- Chunking: write glossary/identifier/permission-matrix docs as many short, self-contained entries
  (~500 chars each, one term/row per entry) so each becomes a clean retrieval unit; write prose/playbook
  docs in coherent ~1500-char sections.
- Deterministic `id` exactly as in the table. (Content hash is computed by the ingestion pipeline — you
  do not add it.)

## Deliverables
1. The markdown files under `src/main/resources/rag/`.
2. A YAML snippet to paste into `application.yml` `mcp.rag.preload.docs` (id, source-path, rag-scope,
   required-permissions) for all new docs.
3. A short `SOURCES.md` (or a footer in each doc) listing, per document, which repo files you grounded
   it in, plus any `TODO(verify)` items you could not confirm.

## Definition of done
- All 11 documents written, scoped, and permission-tagged per the table.
- Every permission code and tool reference verified against the repo (or flagged TODO).
- No customer-facing content; `workorder` one word; permission disclaimer present.
- The `mcp.rag.preload.docs` YAML snippet provided and consistent with the file paths/ids.
