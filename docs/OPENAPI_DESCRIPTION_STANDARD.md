# OpenAPI Operation Description Standard

This document is the fleet-wide template for the two ADR-0042 requirements that no module
satisfied before it existed (see [#1263](https://github.com/louisburroughs/durion-positivity-backend/issues/1263)):

- **§1 `description`** — a structured tool invocation guide of 4–8 sentences covering seven required elements.
- **§3 request body** — swagger `@RequestBody` with `description`, explicit `required`, and at least one example.

ADR-0042 (`durion/docs/adr/0042-openapi-annotation-standards.adr.md`) states *what* must be
present. This document fixes *how* it is written, so that 964 operations across 26 modules read
as one voice rather than 26 interpretations, and so that the depth can be machine-checked by
`pos-openapi-validation` instead of remaining aspirational.

## Why the wording is prescriptive

These descriptions are consumed by the MCP server to choose tools. Their value is comparative: an
agent picking between `createInvoice` and `createCreditMemo` benefits only if both descriptions
answer the same questions in the same order. A description that is merely *long* is worth nothing
if the agent has to guess where the preconditions are. So the template fixes sentence order and
lead-in phrases; the validator checks for those lead-ins, which is also what makes the rule
enforceable without natural-language understanding.

## The template

Write the description as 4–8 sentences, in this order, using these lead-ins:

```text
<Primary action.>
Use this tool when <trigger>; do not use <alternative tool> for <that other purpose>.
Preconditions: <state or data that must already exist>.
Required inputs: <mandatory fields, formats, constraints, non-obvious defaults>.
<Side effects — "Emits <EVENT_ID> ..." or "No events are emitted; ...">
Returns <code> when <business condition>, <code> when <business condition>.
```

Mapping to the seven ADR-0042 elements:

| # | ADR-0042 element | Where it lives in the template |
| --- | --- | --- |
| 1 | Primary action | First sentence |
| 2 | When to use | `Use this tool when ...` |
| 3 | Required preconditions | `Preconditions: ...` |
| 4 | Key input expectations | `Required inputs: ...` |
| 5 | Side effects | `Emits ...` / `No events are emitted ...` |
| 6 | Error conditions | `Returns 409 when ...` |
| 7 | What NOT to do | `do not use ... ` clause, or `... instead` |

### Writing rules

- **Third person, present tense, declarative.** "Creates a supplier account", not "Create" or
  "This endpoint will create".
- **Name the alternative tool by `operationId`.** "do not use `updateSupplierAccount` for this" is
  actionable; "do not use this for updates" is not.
- **Preconditions are checkable state**, not restatements of validation: "the supplier profile must
  be ACTIVE", not "the request must be valid".
- **Required inputs name fields and formats**, and state defaults that a caller cannot infer:
  "`supplierId` (UUID) and `code`; `enabled` defaults to true".
- **Side effects are concrete**: the event id emitted, records touched, asynchrony the caller must
  poll for. When there are none, say so — `No events are emitted; the change takes effect
  immediately.` Silence is indistinguishable from an omission.
- **Error sentence lists business failures**, not the framework's generic set. 400 for malformed
  payloads and 401/403 for auth are documented in `@ApiResponses` and need not be narrated;
  409/404/422 conditions do, because the caller can self-correct from them.
- **No markdown, no line-leading bullets.** The MCP server flattens the text.
- **4–8 sentences.** Under four means an element is missing; over eight means the description is
  being used as a manual.

### Read-only operations

GETs still take all seven elements. The elements do not disappear, they get shorter:

- Preconditions are usually about existence and visibility: "the caller's tenant must own the record".
- Side effects: "No events are emitted; this is a read-only projection."
- Errors: "Returns 404 when no supplier profile exists for the supplied id."

### Text blocks

Use a Java text block and let the lines wrap naturally. The generated YAML folds them into one
string; sentence boundaries, not line boundaries, are what the validator counts.

## Worked exemplars

### Create (state-changing, emits an event)

```java
@Operation(
        operationId = "createSupplierAccount",
        summary = "Create a supplier account",
        description =
                """
                Creates a purchasing account that binds one supplier profile to one location for ordering and invoicing.
                Use this tool when a location is authorised to buy from a supplier for the first time; do not use \
                updateSupplierAccount, which changes an account that already exists.
                Preconditions: the supplier profile must exist and be ACTIVE, the location must exist, and no account \
                may already exist for that supplier and location pair.
                Required inputs: supplierId (UUID), locationId (UUID) and accountNumber; creditLimit is optional and \
                defaults to null, meaning no enforced limit.
                Emits a SUPPLIER_ACCOUNT_CREATE event; no order or invoice records are touched.
                Returns 409 when an account already exists for the supplier and location pair, and 404 when the \
                supplier profile or location cannot be resolved.
                """)
```

### Read (single resource)

```java
@Operation(
        operationId = "getSupplierProfileById",
        summary = "Get a supplier profile",
        description =
                """
                Returns the full supplier profile, including its integration status and contact metadata.
                Use this tool when a supplier id is already known; use listSupplierProfiles instead when searching \
                by name, code or status.
                Preconditions: the supplier profile must exist and be visible to the caller's tenant.
                Required inputs: supplierId (UUID) as a path parameter; there is no request body and no filtering.
                No events are emitted and no state changes; this is a read-only projection.
                Returns 404 when no supplier profile exists for the supplied id.
                """)
```

### State transition (idempotent, tolerant of an outage)

```java
@Operation(
        operationId = "commitTaxDocument",
        summary = "Commit tax document",
        description =
                """
                Commits the provider tax document for an invoice that has been finalised, making the recorded tax \
                filing-visible at the provider.
                Use this tool when an invoice reaches a finalised state; do not use it to recalculate tax, which is \
                calculateTax, and do not use it to reverse a commit, which is voidTaxDocument.
                Preconditions: tax must already have been calculated for this referenceId, and the source invoice \
                must be finalised rather than DRAFT.
                Required inputs: referenceId (UUID) as a path parameter, which is the source invoice id; \
                referenceType is optional and defaults to INVOICE.
                Emits a TAX_COMMIT event and records a provider transaction; the call is idempotent on referenceId, \
                and a provider outage records PENDING_COMMIT rather than failing, leaving the re-commit job to \
                true it up.
                Returns 200 with a PENDING_COMMIT status when the provider is unreachable, so callers must read the \
                returned status rather than treating 200 as a completed commit.
                """)
```

## Request bodies (§3)

Every operation that takes a body must annotate it. `@RequestBody` here is
`io.swagger.v3.oas.annotations.parameters.RequestBody`, which coexists with Spring's
`@org.springframework.web.bind.annotation.RequestBody` on the same parameter:

```java
public ResponseEntity<SupplierAccount> createSupplierAccount(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
                        description = "Supplier account to create, binding one supplier profile to one location.",
                        required = true,
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        examples =
                                                @ExampleObject(
                                                        name = "Minimal account",
                                                        value =
                                                                """
                                                                {"supplierId":"018f8c1e-0000-7000-8000-000000000001",
                                                                 "locationId":"018f8c1e-0000-7000-8000-000000000002",
                                                                 "accountNumber":"ACCT-1001"}
                                                                """)))
                @Valid
                @org.springframework.web.bind.annotation.RequestBody
                SupplierAccountRequest request) {
```

Required:

- `description` — what the body represents, one sentence. Not a repeat of the summary.
- `required` — stated explicitly, even when true.
- at least one `@ExampleObject` with a realistic, schema-valid value. Use UUID v7 shaped ids.

The DTO's own `@Schema` field annotations are covered by ADR-0042 §5 and are not part of this
document; the example exists so an agent can construct a body without resolving the `$ref`.

## Enforcement

`pos-openapi-validation` checks these rules per module. The dimension is `annotationDepth` in
`pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml`:

| `annotationDepth` | Effect |
| --- | --- |
| `STRICT` | Depth findings fail the build in every mode. |
| `REPORT_ONLY` | Depth findings are reported, and fail only under `-Dopenapi.validation.mode=STRICT`. Default when the key is absent. |
| `EXEMPT` | Depth is not checked. Requires a `annotationDepthReason`. |

The checks, and the message each emits:

| Check | Message fragment |
| --- | --- |
| 4–8 sentences | `description has N sentences (ADR-0042 §1 requires 4-8)` |
| Primary action opens the text | `description does not open with a primary-action sentence` |
| When-to-use lead-in | `description missing when-to-use guidance ("Use this tool ...")` |
| Preconditions lead-in | `description missing preconditions ("Preconditions: ...")` |
| Input expectations lead-in | `description missing input expectations ("Required inputs: ...")` |
| Side effects lead-in | `description missing side effects ("Emits ..." or "No events are emitted")` |
| Error conditions | `description missing error conditions ("Returns <code> when ...")` |
| Negative guidance | `description missing negative guidance ("do not use ..." / "... instead")` |
| Request body description | `request body missing description (ADR-0042 §3)` |
| Request body `required` | `request body missing explicit required flag (ADR-0042 §3)` |
| Request body example | `request body missing example (ADR-0042 §3)` |

Run the checks:

```bash
# Blocking findings only (annotationDepth: STRICT modules)
./mvnw -pl pos-openapi-validation -DskipTests=false -Dtest=OpenApiRepositoryValidationTest test

# Full fleet gap, including REPORT_ONLY modules
./mvnw -pl pos-openapi-validation -DskipTests=false \
  -Dopenapi.validation.mode=STRICT -Dtest=OpenApiRepositoryValidationTest test
```

## Rollout

Modules move to `annotationDepth: STRICT` one at a time, each with its spec regenerated in the same
change (`scripts/generate-openapi.sh <module>`). `pos-tax` and `pos-supplier` are the reference
conversions; read their controllers before converting a new module.

Rewriting descriptions is a domain exercise, not a mechanical one — a generic seven-part
description that passes the validator and tells an agent nothing is worse than the one-line
description it replaced, because it costs tokens on every tool-selection prompt. If the
preconditions or side effects of an operation are not known, find out before writing the sentence.
