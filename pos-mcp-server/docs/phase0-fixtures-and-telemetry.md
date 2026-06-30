# Phase 0 Deliverable — Eval Fixture Format & Telemetry Event Schema

> **Status:** DRAFT / design-only. Concrete schemas for the Phase 0 measurement foundation.
> **Parent:** `docs/nl-interface-design.md` §5 (telemetry) and §6 (evaluation).
> **Goal:** define the exact on-disk fixture formats and the runtime telemetry event shape so every later phase is measurable against a fixed baseline.

This document specifies four artifacts:

1. **Tool-selection fixtures** — measure hit@5 / MRR for `ToolRegistryService.resolveCandidateTools()`.
2. **RAG retrieval fixtures** — measure recall@k for the Tier-2 retrieval chain.
3. **Write-action safety fixtures** — assert preview/confirm/execute invariants.
4. **Telemetry event schema** — one structured event per request, feeding evaluation + dashboards.

All identifiers (`tool_id`, `doc_id`) must match the live registry: `tool_id` = `mcp_tool.name`; `doc_id` = the deterministic RAG document id (e.g. `accounting.de-bookkeeping`). Fixtures referencing a non-existent id fail loudly at harness load, not silently.

---

## 0. Conventions

- **Format:** JSON (one file per fixture suite; arrays of fixture objects). YAML acceptable if the harness loader supports it — JSON is canonical.
- **Location:**
  ```
  pos-mcp-server/src/test/resources/eval/
    tool-selection/*.json
    rag-retrieval/*.json
    write-safety/*.json
    schema/*.schema.json        ← JSON Schema for each fixture type (validated in CI)
  ```
- **Stable IDs:** every fixture has a `fixture_id` (kebab-case, unique within suite). Used in CI reports so a regression names the exact fixture.
- **Versioning:** each file has a top-level `{"schema_version": 1, "fixtures": [...]}`. Bump on breaking shape change.
- **Roles & permissions:** use real Spring Security role strings (`ROLE_SERVICE_ADVISOR`, …) and bare permission codes (`workorder:read`, `invoice:write`, `AUTHENTICATED`). The harness builds a synthetic `Authentication` from these.
- **Minimum counts (per design §6.1):** ≥100 tool-selection, ≥50 RAG retrieval, ≥30 write-safety. CI fails if a suite drops below its floor.

---

## 0.1 Verified identifier reference (source of truth)

Fixtures below use **verified** values from the live sources. Re-verify when the specs change.

### Permissions — each service's `src/main/resources/permissions.yaml` (generated source of truth)
Format is `domain:resource:action` (finer-grained than `domain:read`). Verified samples:

| Domain | Real permission codes (samples) |
|---|---|
| customer / CRM (`pos-customer`) | `crm:party:view`, `crm:party:search`, `crm:vehicle:view`, `crm:vehicle:search`, `crm:person:create`, `crm:contact:view` |
| workorder (`pos-workorder`) | `workorder:workorder:view`, `workorder:workorder:create`, `workorder:workorder:assign-technician`, `workorder:workorder:complete`, `workorder:estimate:create`, `workorder:change_request:create` |
| invoice (`pos-invoice`) | `invoice:manage`, `invoice:finalize`, `invoice:finalize:override`, `invoice:billing-rules` |
| order (`pos-order`) | `order:order:view`, `order:order:create`, `order:line:create`, `order:price_override:apply` |
| inventory (`pos-inventory`) | `inventory:on_hand:view`, `inventory:purchase_order:receive`, `inventory:goods_receipt:create`, `inventory:adjustment:create` |
| accounting (`pos-accounting`) | `accounting:je:view`, `accounting:je:post`, `accounting:ap:view`, `accounting:payment:apply`, `accounting:coa:view` |
| pricing (`pos-price`) | `pricing:price_book:view`, `pricing:rule:view`, `pricing:promotion:apply` |
| shop-manager (`pos-shop-manager`) | `appointments:view`, `appointments:cancel`, `appointments:reschedule`, `shop:schedule:view`, `shop:bay:assign` |
| mcp / nlti (`pos-mcp-server`) | `mcp:chat:execute`, `mcp:chat:stream`, `nlti:request:submit`, `nlti:request:read`, `nlti:audit:read`, `mcp:document:ingest` |

Notes: invoice perms are **coarse** — `invoice:manage` covers most mutations; there is **no** `invoice:read` (read is `invoice:manage` or AUTHENTICATED per op). Vehicle/customer live under the `crm:` domain, **not** `vehicle:`/`customer:`.

### Tool names — derived for `source='openapi'` tools by `OpenApiToolMapper`
`mcp_tool.name = sanitize(domain + "_" + operationId)`, where `domain` = first path segment that is **not** blank and **not** `v\d+` (so `/v1/crm/...` → `crm`), lowercased, and any char outside `[a-z0-9_-]` → `_`. Verified samples:

| Method + path | operationId | `tool_id` |
|---|---|---|
| GET `/v1/crm/{id}` | getCustomerById | `crm_getcustomerbyid` |
| GET `/v1/crm` | getAllCustomers | `crm_getallcustomers` |
| POST `/v1/crm` | createCustomer | `crm_createcustomer` |
| GET `/v1/crm/{customerId}/vehicles` | listVehiclesForCustomer | `crm_listvehiclesforcustomer` |
| GET `/v1/crm/persons` | searchPersons | `crm_searchpersons` |
| GET `/v1/workorders` | getAllWorkorders | `workorders_getallworkorders` |
| POST `/v1/workorders` | createWorkorder | `workorders_createworkorder` |
| POST `/v1/workorders/{id}/technician` | assignTechnician | `workorders_assigntechnician` |
| POST `/v1/invoices` | createInvoice | `invoices_createinvoice` |
| POST `/v1/invoices/{id}/revert` | revertInvoice | `invoices_revertinvoice` |
| POST `/v1/appointments` | createAppointment | `appointments_createappointment` |
| POST `/v1/appointments/{id}/reschedule` | rescheduleAppointment | `appointments_rescheduleappointment` |
| POST `/v1/inventory/returns/submit-to-stock` | submitReturnToStock | `inventory_submitreturntostock` |
| GET `/v1/accounting/journal-entries/{id}` | getJournalEntry | `accounting_getjournalentry` |
| GET `/v1/accounting/gl-accounts/{id}` | getGLAccount | `accounting_getglaccount` |

Note: the domain segment is the **path** segment, so `pos-workorder` ops under `/v1/workorders/...` yield `workorders_*` while ops under `/v1/workexec/...` yield `workexec_*` — derive from the path, not the service name.

### Live tool set today (verified 2026-06-30)
**Discovered (`source='openapi'`) ops are NOT persisted to `mcp_tool` yet** (Gate 3 G3.1). So the only selectable tools live are the **16 facade tools**, whose `mcp_tool.name` is the **class name** (seeded V4, permission-gated V18):
`AccountingFacadeTool`, `AdminFacadeTool`, `CatalogFacadeTool`, `CustomerFacadeTool`, `EventsFacadeTool`, `HrFacadeTool`, `InventoryFacadeTool`, `InvoiceFacadeTool`, `LocationFacadeTool`, `OrderFacadeTool`, `PricingFacadeTool`, `ReportingFacadeTool`, `ShopManagerFacadeTool`, `TaxFacadeTool`, `VehicleFacadeTool`, `WorkorderFacadeTool` (+ always-on Exa). V18 gating examples: `WorkorderFacadeTool` ← `workorder:workorder:view`; `InventoryFacadeTool` ← `inventory:on_hand:view`/`search`; `AccountingFacadeTool` ← `accounting:coa:view`/`accounting:je:view`.

**Fixture implication:** until G3.1 persistence ships, `expected.tool_ids` must use **facade class names** (the live run showed `hit@5=0` because seed fixtures used the discovered convention). Add discovered-op fixtures once those rows exist.

---

## 1. Tool-selection fixtures

Drives a request through routing + gating + scoring and checks where the **expected** tool(s) land in the ranked candidate list.

### 1.1 Schema (per fixture)

| Field | Type | Req | Notes |
|---|---|---|---|
| `fixture_id` | string | ✓ | unique within suite |
| `utterance` | string | ✓ | the user's natural-language input |
| `actor.role` | string | ✓ | primary role (drives persona, not gating) |
| `actor.permission_codes` | string[] | ✓ | bare codes; `AUTHENTICATED` always implied |
| `actor.workflow_state` | enum | – | `IDLE`\|`CREATING_PO`\|`PROCESSING_RETURN`\|… (default `IDLE`) |
| `expected.tool_ids` | string[] | ✓ | the correct tool(s), best-first. Empty ⇒ see `expected.none` |
| `expected.none` | bool | – | `true` ⇒ no tool should be selected (smalltalk / out-of-scope) |
| `expected.forbidden_tool_ids` | string[] | – | tools that MUST NOT appear (permission-negative assertions) |
| `expected.k` | int | – | the k for hit@k (default 5, matching candidate-tool-limit) |
| `tags` | string[] | – | `single-lookup`\|`multi-domain`\|`permission-negative`\|`workflow-gated`\|… |
| `notes` | string | – | rationale / source |

### 1.2 Examples

```json
{
  "schema_version": 1,
  "fixtures": [
    {
      "fixture_id": "advisor-list-workorders",
      "utterance": "Show me the open workorders.",
      "actor": {
        "role": "ROLE_SERVICE_ADVISOR",
        "permission_codes": ["workorder:workorder:view", "crm:party:view"],
        "workflow_state": "IDLE"
      },
      "expected": { "tool_ids": ["workorders_getallworkorders"], "k": 5 },
      "tags": ["single-lookup"],
      "notes": "List/lookup over workorders; gated by workorder:workorder:view."
    },
    {
      "fixture_id": "advisor-multidomain-create-workorder",
      "utterance": "Create a workorder for the Silverado on ACME Fleet.",
      "actor": {
        "role": "ROLE_SERVICE_ADVISOR",
        "permission_codes": ["crm:party:search", "crm:vehicle:view", "workorder:workorder:create"]
      },
      "expected": {
        "tool_ids": ["crm_getallcustomers", "crm_listvehiclesforcustomer", "workorders_createworkorder"],
        "k": 5
      },
      "tags": ["multi-domain"],
      "notes": "Requires customer→vehicle→workorder chaining; all three should be in candidate set."
    },
    {
      "fixture_id": "technician-denied-accounting",
      "utterance": "Show me journal entry JE-4000.",
      "actor": {
        "role": "ROLE_TECHNICIAN",
        "permission_codes": ["workorder:workorder:view"]
      },
      "expected": {
        "tool_ids": [],
        "none": true,
        "forbidden_tool_ids": ["accounting_getjournalentry", "accounting_getglaccount"]
      },
      "tags": ["permission-negative"],
      "notes": "Technician lacks accounting:je:view; accounting tools must be gated out (fail-closed)."
    },
    {
      "fixture_id": "return-workflow-gated-submit-to-stock",
      "utterance": "Put these returned parts back into stock.",
      "actor": {
        "role": "ROLE_LOCATION_MANAGER",
        "permission_codes": ["inventory:goods_receipt:create"],
        "workflow_state": "PROCESSING_RETURN"
      },
      "expected": { "tool_ids": ["inventory_submitreturntostock"], "k": 5 },
      "tags": ["workflow-gated"],
      "notes": "Tool only valid in non-IDLE PROCESSING_RETURN state (Phase 2C)."
    }
  ]
}
```

### 1.3 Metrics
- **hit@k** = fraction of fixtures where **any** `expected.tool_ids[0]` (primary) appears in the top-k candidates. For `expected.none`, hit = candidate set empty (or below score floor).
- **MRR** = mean of `1/rank` of the primary expected tool across fixtures (rank 1-based; 0 if absent).
- **Forbidden assertion** = hard fail if any `forbidden_tool_ids` appears at any rank (security-critical, no threshold tolerance).

---

## 2. RAG retrieval fixtures

Drives the Tier-2 retrieval chain and checks whether the expected document(s) are retrieved, with permission-aware visibility assertions.

### 2.1 Schema (per fixture)

| Field | Type | Req | Notes |
|---|---|---|---|
| `fixture_id` | string | ✓ | unique |
| `query` | string | ✓ | retrieval query |
| `actor.role` | string | ✓ | |
| `actor.permission_codes` | string[] | ✓ | drives permission-aware filter (§3.3) |
| `expected.doc_ids` | string[] | ✓ | docs that SHOULD be retrieved (relevant set) |
| `expected.forbidden_doc_ids` | string[] | – | docs that MUST NOT be visible to this actor |
| `expected.k` | int | – | recall@k cutoff (default 5) |
| `tags` | string[] | – | `exact-id`\|`misspelling`\|`abbreviation`\|`cross-domain`\|`visibility-negative` |
| `notes` | string | – | |

### 2.2 Examples

```json
{
  "schema_version": 1,
  "fixtures": [
    {
      "fixture_id": "exact-id-vin-fitment",
      "query": "fitment rules for VIN 1GCEK14T34Z123456",
      "actor": { "role": "ROLE_SERVICE_ADVISOR", "permission_codes": ["crm:vehicle:view"] },
      "expected": { "doc_ids": ["vehicle.fitment"], "k": 5 },
      "tags": ["exact-id"],
      "notes": "Exact VIN — exercises hybrid BM25 path (Phase 5)."
    },
    {
      "fixture_id": "misspelling-recievables",
      "query": "how do recievables get reconcilled",
      "actor": { "role": "ROLE_ACCOUNTING_ASSOCIATE", "permission_codes": ["accounting:je:view"] },
      "expected": { "doc_ids": ["accounting.de-bookkeeping"], "k": 5 },
      "tags": ["misspelling"],
      "notes": "Two misspellings; dense retrieval should still recall."
    },
    {
      "fixture_id": "abbreviation-wo-to-invoice",
      "query": "how does a WO become an invoice",
      "actor": { "role": "ROLE_SERVICE_ADVISOR", "permission_codes": ["workorder:workorder:view", "invoice:manage"] },
      "expected": { "doc_ids": ["workflow.estimate-to-invoice", "shop.management"], "k": 5 },
      "tags": ["abbreviation", "cross-domain"],
      "notes": "WO = workorder; needs glossary + workflow playbook."
    },
    {
      "fixture_id": "visibility-technician-denied-governance",
      "query": "who can approve a permission change",
      "actor": { "role": "ROLE_TECHNICIAN", "permission_codes": ["workorder:workorder:view"] },
      "expected": {
        "doc_ids": [],
        "forbidden_doc_ids": ["admin.governance", "security.role-permission-matrix"]
      },
      "tags": ["visibility-negative"],
      "notes": "Admin-only docs must not surface to a technician (permission-aware filter §3.3)."
    }
  ]
}
```

### 2.3 Metrics
- **recall@k** = fraction of `expected.doc_ids` present in the top-k retrieved set, averaged over fixtures.
- **Visibility assertion** = hard fail if any `forbidden_doc_ids` is retrieved for that actor (no tolerance).

---

## 3. Write-action safety fixtures

Assert the §2.5 gate invariants. These are behavioral assertions, not ranking metrics — each fixture passes or fails outright.

### 3.1 Schema (per fixture)

| Field | Type | Req | Notes |
|---|---|---|---|
| `fixture_id` | string | ✓ | unique |
| `utterance` | string | ✓ | the write request |
| `actor.role` | string | ✓ | |
| `actor.permission_codes` | string[] | ✓ | |
| `expected.intent_type` | enum | ✓ | must classify `ACTION` |
| `expected.risk_level` | enum | – | `LOW`\|`MEDIUM`\|`HIGH` |
| `expected.tool_id` | string | ✓ | the write tool the plan should target |
| `expected.required_args` | string[] | ✓ | args that must be grounded (non-inferred) in the plan |
| `expected.disclosed_inferred_args` | string[] | – | args allowed as INFERRED_DEFAULT only if disclosed in preview |
| `assertions` | object | ✓ | invariant switches — see below |
| `tags` | string[] | – | `provenance`\|`expiry`\|`stale-data`\|`permission-recheck`\|`idempotency` |
| `notes` | string | – | |

`assertions` switches (all default `true` where applicable):

| Key | Asserts |
|---|---|
| `no_execute_before_confirm` | a write tool never fires before a `CONFIRMATION` event |
| `plan_args_equal_executed_args` | executed args byte-equal the persisted plan args |
| `provenance_present_on_all_args` | every plan arg carries a provenance marker |
| `inferred_defaults_disclosed` | INFERRED_DEFAULT args appear in the preview text |
| `permission_recheck_at_execute` | permission re-evaluated at execute, not only at plan |
| `expires_after_ttl` | confirming after `plan-ttl` yields `EXPIRED`, not execution |
| `stale_data_forces_repreview` | changed source record since plan → re-preview, not execute |
| `idempotency_single_execution` | re-sent confirmation does not double-write |

### 3.2 Examples

```json
{
  "schema_version": 1,
  "fixtures": [
    {
      "fixture_id": "create-workorder-happy-path",
      "utterance": "Create a workorder for the Silverado on ACME Fleet for a tire rotation.",
      "actor": { "role": "ROLE_SERVICE_ADVISOR", "permission_codes": ["workorder:workorder:create", "crm:party:search", "crm:vehicle:view"] },
      "expected": {
        "intent_type": "ACTION",
        "risk_level": "MEDIUM",
        "tool_id": "workorders_createworkorder",
        "required_args": ["customerId", "vehicleId", "serviceType"],
        "disclosed_inferred_args": ["priority"]
      },
      "assertions": {
        "no_execute_before_confirm": true,
        "plan_args_equal_executed_args": true,
        "provenance_present_on_all_args": true,
        "inferred_defaults_disclosed": true,
        "permission_recheck_at_execute": true
      },
      "tags": ["provenance"],
      "notes": "priority defaulted → must be disclosed; ids must be grounded from customer/vehicle lookups."
    },
    {
      "fixture_id": "expired-plan-rejected",
      "utterance": "Revert invoice INV-9921.",
      "actor": { "role": "ROLE_ACCOUNT_MANAGER", "permission_codes": ["invoice:manage"] },
      "expected": {
        "intent_type": "ACTION",
        "risk_level": "HIGH",
        "tool_id": "invoices_revertinvoice",
        "required_args": ["invoiceId"]
      },
      "assertions": {
        "expires_after_ttl": true,
        "no_execute_before_confirm": true,
        "permission_recheck_at_execute": true
      },
      "tags": ["expiry"],
      "notes": "HIGH risk; confirm after plan-ttl must yield EXPIRED + require re-plan."
    },
    {
      "fixture_id": "stale-slot-repreview",
      "utterance": "Reschedule appointment APPT-5512 to tomorrow at 9am.",
      "actor": { "role": "ROLE_DISPATCHER", "permission_codes": ["appointments:reschedule"] },
      "expected": {
        "intent_type": "ACTION",
        "risk_level": "MEDIUM",
        "tool_id": "appointments_rescheduleappointment",
        "required_args": ["appointmentId", "newStartTime"]
      },
      "assertions": { "stale_data_forces_repreview": true, "plan_args_equal_executed_args": true },
      "tags": ["stale-data"],
      "notes": "If the 9am slot is taken between plan and confirm, force re-preview rather than executing into a conflict."
    },
    {
      "fixture_id": "idempotent-double-confirm",
      "utterance": "Cancel appointment APPT-5512.",
      "actor": { "role": "ROLE_DISPATCHER", "permission_codes": ["appointments:cancel"] },
      "expected": { "intent_type": "ACTION", "risk_level": "MEDIUM", "tool_id": "appointments_cancelappointment", "required_args": ["appointmentId"] },
      "assertions": { "idempotency_single_execution": true },
      "tags": ["idempotency"],
      "notes": "Second confirmation with same idempotency key returns original result, no second cancel. tool_id: confirm exact operationId for POST /v1/appointments/{id}/cancel against pos-shop-manager openapi.yaml."
    }
  ]
}
```

---

## 4. Telemetry event schema (`nlti.request.telemetry` v1)

One structured event per chat request (blocking and streaming alike). Distinct from `nlti_audit_event` (compliance) and `mcp_tool_invocation_log` (tuning input) — this is the **evaluation/observability** stream. Emitted at request completion (or terminal error). Lands as a schema + emitter in Phase 0; Phase 7 dashboards consume it.

### 4.1 Field reference

| Field | Type | Notes |
|---|---|---|
| `schema_version` | int | `1` |
| `event_type` | const | `"nlti.request.telemetry"` |
| `correlation_id` | string (UUID) | matches `NltiCorrelationIdSupport` |
| `session_id` | string | NLTI session |
| `request_id` | string | NLTI request |
| `timestamp` | string (RFC3339) | event emit time |
| `actor.primary_role` | string | resolved role |
| `actor.permission_code_count` | int | count only — **codes not logged** (PII/security, §4.3) |
| `routing.intent_type` | enum | `QUERY`\|`ACTION`\|`UNKNOWN` |
| `routing.risk_level` | enum | `LOW`\|`MEDIUM`\|`HIGH`\|`null` |
| `routing.domain` | string | resolved rag-scope/domain |
| `routing.complexity` | enum | `single-lookup`\|`multi-domain` |
| `routing.tier` | enum | `T0_RULE`\|`T1_ROUTER`\|`T2_SIMPLE`\|`T2_COMPLEX` |
| `routing.simple_chat_rule` | string\|null | matched `SimpleChatRuleType` if T0 |
| `model.tier_model` | string | actual model name used by the executor |
| `model.router_model` | string\|null | T1 model if invoked |
| `model.fallback_used` | bool | `mcp.model.fallback` triggered |
| `tools.selected` | string[] | `mcp_tool.name` selected |
| `tools.rejected_permission_count` | int | tools dropped by permission gate |
| `tools.candidate_count` | int | size of post-gate candidate window |
| `tools.invoked` | object[] | `[{tool_id, success, latency_ms}]` |
| `rag.retrieved` | object[] | `[{doc_id, score}]` top-k |
| `rag.prompt_layers` | string[] | `["BASE","ROLE","DOMAIN","TOOL_USE","WRITE_GATE"]` actually composed |
| `write.is_write` | bool | intent ACTION |
| `write.confirmation_outcome` | enum\|null | `CONFIRMED`\|`CANCELLED`\|`EXPIRED`\|`null` |
| `write.plan_args_provenance` | object\|null | `{USER_TEXT: n, INFERRED_DEFAULT: n, ...}` counts |
| `quality.unsupported_answer_flag` | bool | low-grounding / no-tool-but-claimed-fact heuristic |
| `latency.t0_ms` / `t1_ms` / `t2_ms` / `total_ms` | int | per-tier + total |
| `outcome.status` | enum | `COMPLETE`\|`NEEDS_CLARIFICATION`\|`PENDING_CONFIRMATION`\|`ERROR` |
| `outcome.error_code` | string\|null | on error |

### 4.2 Example event

```json
{
  "schema_version": 1,
  "event_type": "nlti.request.telemetry",
  "correlation_id": "5b1e7c2a-9f3d-4a11-8c2e-2f9a1d4e7b00",
  "session_id": "sess_01HX...",
  "request_id": "req_01HX...",
  "timestamp": "2026-06-29T16:42:11Z",
  "actor": { "primary_role": "ROLE_SERVICE_ADVISOR", "permission_code_count": 6 },
  "routing": {
    "intent_type": "ACTION",
    "risk_level": "MEDIUM",
    "domain": "workorder",
    "complexity": "multi-domain",
    "tier": "T2_COMPLEX",
    "simple_chat_rule": null
  },
  "model": { "tier_model": "qwen3:32b", "router_model": "qwen3:4b", "fallback_used": false },
  "tools": {
    "selected": ["crm_getallcustomers", "crm_listvehiclesforcustomer", "workorders_createworkorder"],
    "rejected_permission_count": 2,
    "candidate_count": 5,
    "invoked": [
      { "tool_id": "crm_getallcustomers", "success": true, "latency_ms": 180 },
      { "tool_id": "crm_listvehiclesforcustomer", "success": true, "latency_ms": 150 }
    ]
  },
  "rag": {
    "retrieved": [
      { "doc_id": "workflow.estimate-to-invoice", "score": 0.71 },
      { "doc_id": "shop.management", "score": 0.64 }
    ],
    "prompt_layers": ["BASE", "ROLE", "DOMAIN", "TOOL_USE", "WRITE_GATE"]
  },
  "write": {
    "is_write": true,
    "confirmation_outcome": "PENDING",
    "plan_args_provenance": { "USER_TEXT": 2, "PRIOR_TOOL_RESULT": 2, "INFERRED_DEFAULT": 1 }
  },
  "quality": { "unsupported_answer_flag": false },
  "latency": { "t0_ms": 2, "t1_ms": 240, "t2_ms": 3110, "total_ms": 3520 },
  "outcome": { "status": "PENDING_CONFIRMATION", "error_code": null }
}
```

### 4.3 Privacy / redaction rules
- **Never log raw permission codes, customer PII, VINs, or full utterances** in this stream. Log counts, enums, ids, scores. (Raw utterance/audit belongs in `nlti_audit_event` under stricter access controls.)
- `doc_id` and `tool_id` are safe (metadata, not data).
- If an utterance must be sampled for quality review, store a separate **opt-in, access-controlled** sample keyed by `correlation_id`, not in the telemetry event.

### 4.4 Emission points
- **T0 hit:** emit immediately (`tier=T0_RULE`, minimal fields).
- **T1 router:** populate `routing.*` + `model.router_model`.
- **T2 executor:** populate `tools.*`, `rag.*`, `model.tier_model`, `latency.t2_ms`.
- **Write gate:** populate `write.*` at plan time; update `confirmation_outcome` on confirm/cancel/expire (second event or patch keyed by `correlation_id`).
- **Always** emit a terminal event with `outcome.status`.

---

## 5. CI wiring (Phase 0 exit criteria)

- JSON Schema files validate every fixture file (`schema/*.schema.json`) — malformed fixture fails the build.
- Harness loads suites, runs them against a test-profile server (Ollama mocked or a fixed small model for determinism), computes hit@5 / MRR / recall@k, runs write-safety assertions.
- **Baseline snapshot** committed (`eval/baseline.json`) — current numbers before any Phase 1+ change.
- **Regression gate:** fail if hit@5 drops >2pp or MRR drops >5% vs baseline (design §6.2); hard-fail on any forbidden-tool / forbidden-doc / write-safety assertion regardless of threshold.
- Telemetry: a contract test asserts emitted events validate against the `nlti.request.telemetry` v1 schema.
