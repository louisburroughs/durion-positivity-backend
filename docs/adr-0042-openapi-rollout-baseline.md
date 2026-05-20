# ADR-0042 OpenAPI Rollout Baseline

## Purpose

This baseline captures the current REST/OpenAPI footprint for the ADR-0042 rollout, the enforcement rules the rollout will apply, and the initial gap classification used to sequence remediation across modules.

It exists to support the cross-module rollout tied to issue #645, where the gateway aggregate spec and `pos-mcp-server` OpenAPI discovery need complete, stable endpoint metadata.

## Shared Enforcement Rules

### Controller rules

- Public REST controller classes must expose `@Tag` with a stable, domain-appropriate name and description.
- Public REST endpoints must expose `@Operation(summary = ..., description = ...)`.
- Response documentation must use repeatable `@ApiResponse` annotations directly on the endpoint.
- Endpoint parameter and request-body metadata should use `@Parameter` and explicit request-body descriptions where generated contracts would otherwise be ambiguous.
- OpenAPI annotations must match actual endpoint behavior: status codes, validation behavior, and payload shapes.

### DTO rules

- Public request and response DTOs must expose `@Schema` on the type.
- Externally significant, ambiguous, or validation-sensitive DTO fields must expose field-level `@Schema` metadata.
- DTO examples and required flags should reflect real contract expectations.

### Generated spec rules

- Every generated operation must carry a non-empty `summary`.
- Every generated operation must carry a non-empty `description`.
- Generated status code documentation must align with actual controller behavior.
- Module-level specs must be attributable back to the source module when validation fails.
- The aggregate spec must remain usable for downstream discovery consumers, especially `pos-mcp-server`.

### Validation ownership

- Shared validation is owned by `pos-openapi-validation`, which evaluates generated module specs and the aggregate spec using the committed module policy in `pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml`.
- `scripts/generate-openapi.sh` remains the generation entrypoint, while Maven validation tests in `pos-openapi-validation` own `report` and `strict` enforcement.

## Module Inventory and Rollout Waves

### Wave 1: MCP-critical modules

- `pos-accounting`
- `pos-order`
- `pos-workorder`
- `pos-inventory`
- `pos-price`
- `pos-customer`
- `pos-location`
- `pos-people`

These modules are highest priority because they either contribute heavily to the gateway aggregate or are directly relevant to the issue #645 MCP discovery path.

### Wave 2: remaining business-facing modules

- `pos-catalog`
- `pos-shop-manager`
- `pos-invoice`
- `pos-bulk-loader`
- `pos-vehicle-inventory`
- `pos-event-receiver`
- `pos-vehicle-fitment`
- `pos-image`
- `pos-documents`
- `pos-security-service`

### Wave 3: exceptions, reference modules, and stubs

- `pos-vehicle-reference-carapi`
- `pos-vehicle-reference-nhtsa`
- `pos-tax`
- `pos-inquiry`
- `pos-api-gateway` (aggregate consumer/generator surface, not a normal REST producer)
- `pos-mcp-server` (aggregate consumer and downstream validator)

## Current Generated Module Set

The current `scripts/generate-openapi.sh --dry-run` module list is:

- `pos-accounting`
- `pos-api-gateway`
- `pos-bulk-loader`
- `pos-catalog`
- `pos-customer`
- `pos-documents`
- `pos-event-receiver`
- `pos-image`
- `pos-inquiry`
- `pos-inventory`
- `pos-invoice`
- `pos-location`
- `pos-mcp-server`
- `pos-order`
- `pos-people`
- `pos-price`
- `pos-security-service`
- `pos-shop-manager`
- `pos-tax`
- `pos-vehicle-fitment`
- `pos-vehicle-inventory`
- `pos-workorder`

## Initial Gap Classification

### Blocking gaps

Blocking gaps are issues that directly threaten aggregate-first discovery or prevent ADR-0042 enforcement from becoming machine-checkable.

- Any operation missing `summary` or `description`
- Any module whose OpenAPI output cannot be attributed back to the source module in validation output
- Any aggregate-generation condition that hides source-module metadata problems
- Any `pos-mcp-server` discovery path that cannot surface or reject bad operations deterministically

### Quality gaps

Quality gaps do not necessarily block aggregate generation, but they weaken contract quality and should be remediated during the rollout.

- Incomplete response documentation for real endpoint outcomes
- DTOs that rely on inferred schema only, where field-level metadata would clarify the contract
- Weak or inconsistent `@Tag` naming/description quality
- Missing parameter or request-body descriptions on externally meaningful inputs

### Deferred gaps

Deferred gaps are improvements worth tracking but not required to unblock the rollout.

- Cosmetic wording improvements where summary/description already exist and are usable
- Low-value schema embellishments that do not affect contract clarity or MCP discovery
- Modules intentionally excluded from the gateway or aggregate path, once that exclusion is documented and confirmed

## Initial Metadata Scan of Committed Module Specs

The current committed `openapi.yaml` files show the following operation-level metadata gaps:

| Module | Operations | Missing summary | Missing description | Notes |
| --- | ---: | ---: | ---: | --- |
| pos-accounting | 90 | 0 | 0 | Clean in committed spec |
| pos-api-gateway | 0 | 0 | 0 | No normal path inventory in module spec; aggregate generator already warns this file has no `paths` section |
| pos-bulk-loader | 18 | 0 | 0 | Clean in committed spec |
| pos-catalog | 54 | 0 | 7 | Description-only cleanup needed |
| pos-customer | 41 | 0 | 0 | Clean in committed spec |
| pos-documents | 1 | 1 | 1 | Blocking gap |
| pos-event-receiver | 12 | 0 | 0 | Clean in committed spec |
| pos-image | 2 | 0 | 0 | Clean in committed spec |
| pos-inquiry | 0 | 0 | 0 | Stub/placeholder surface |
| pos-inventory | 72 | 0 | 0 | Clean in committed spec |
| pos-invoice | 15 | 4 | 7 | Blocking gap |
| pos-location | 37 | 4 | 10 | Blocking gap |
| pos-mcp-server | 16 | 5 | 8 | Blocking gap and consumer-adjacent |
| pos-order | 14 | 0 | 0 | Clean in committed spec |
| pos-people | 44 | 0 | 0 | Clean in committed spec |
| pos-price | 20 | 0 | 4 | Description-only cleanup needed |
| pos-security-service | 85 | 0 | 0 | Clean in committed spec |
| pos-shop-manager | 23 | 2 | 2 | Blocking gap |
| pos-tax | 2 | 0 | 0 | Clean in committed spec |
| pos-vehicle-fitment | 11 | 0 | 0 | Clean in committed spec |
| pos-vehicle-inventory | 21 | 0 | 0 | Clean in committed spec |
| pos-workorder | 92 | 2 | 15 | Blocking gap |

## Immediate Rollout Implications

- Shared validation should fail or report at least these blocking modules immediately: `pos-documents`, `pos-invoice`, `pos-location`, `pos-mcp-server`, `pos-shop-manager`, and `pos-workorder`.
- `pos-catalog` and `pos-price` already show useful summary coverage but still need description completion.
- `pos-api-gateway` needs special handling because it participates in aggregate generation without behaving like a normal module spec producer.
- `pos-mcp-server` belongs in both the remediation and consumer-validation tracks because its own module spec has metadata gaps and it consumes the aggregate downstream.
