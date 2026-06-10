# Customer Requirements Endpoint Design

## Problem

`pos-workorder` already calls `GET /v1/customers/{id}/requirements-met` through `CustomerValidationClient.checkRequirementsMet(...)` when creating a workorder. That endpoint does not exist in `pos-customer`, so the caller currently fails closed for every request.

Issue `#653` defines the intended contract: `pos-customer` should expose a raw boolean endpoint that answers whether a customer is in good standing to open a new workorder.

## Decision

Add a dedicated internal endpoint in `pos-customer`:

- `GET /v1/customers/{id}/requirements-met`
- secured with `crm:party:view`
- returns a raw JSON boolean body
- returns `404` when the customer does not exist

The standing evaluation logic will live in a focused internal service instead of being inlined into a controller.

## Scope

### In scope

- Add a new internal controller route in `pos-customer`
- Add a small internal service to resolve a party and evaluate standing
- Reuse existing `CommercialPartyServiceImpl` then `PersonPartyServiceImpl` lookup behavior
- Add contract/integration tests for success, false cases, not-found, and auth

### Out of scope

- Any `pos-workorder` client path or header changes
- Changing the existing `/v1/crm/...` CRUD routes
- Introducing a new permission
- Adding approval-related customer endpoints

## Endpoint Contract

### Route

- `GET /v1/customers/{id}/requirements-met`

### Authorization

- `@PreAuthorize("hasAuthority('crm:party:view')")`
- continue using the current gateway-style `X-User` and `X-Authorities` header convention already exercised by `pos-customer` contract tests

### Responses

- `200 OK` with raw body `true` or `false`
- `404 Not Found` when no party exists for the given `id`
- `401` or `403` continue to follow the existing security behavior for `pos-customer`

### Body shape

Return a raw JSON boolean, not an envelope:

- `true`
- `false`

This preserves the current caller contract in `CustomerValidationClient`, which uses `.retrieve().body(Boolean.class)`.

## Standing Rules

The endpoint answers whether a party is allowed to open a new workorder.

### Rule 1: Party must exist

Resolve the customer exactly the way `CustomerController.getCustomerById(...)` already does:

1. try `CommercialPartyServiceImpl`
2. fall back to `PersonPartyServiceImpl`

If neither service finds a party, return `404`.

### Rule 2: Status must be ACTIVE

Return `false` when `AbstractParty.status` is:

- `INACTIVE`
- `ON_HOLD`
- `MERGED`

Return `true` only when status is `ACTIVE`, subject to the commercial-party rule below.

### Rule 3: Commercial credit hold blocks workorders

For `CommercialParty`, return `false` when:

- `billingRules != null`
- `billingRules.creditHold == true`

If `billingRules` is absent or `creditHold` is null/false, the commercial-party credit-hold rule does not block the customer.

### Rule 4: Person parties have no credit-hold overlay

For `PersonParty`, standing depends only on `status == ACTIVE`.

## Design

### Controller

Add a dedicated controller under `com.positivity.customer.internal.controller` for the internal customer-standing contract rather than extending `CustomerController`.

Reasoning:

- preserves the current `/v1/crm` controller semantics
- keeps the internal service-to-service route explicit
- avoids mixing a boolean workflow gate into the CRUD controller

The controller should:

- accept `UUID id`
- call the new standing service
- translate missing customer to `404`
- return `ResponseEntity<Boolean>` with the raw boolean body

### Service

Add a focused internal service under `com.positivity.customer.internal.service` to:

- resolve the party entity
- evaluate standing rules

The service should expose two behaviors:

- resolve the party by id using current module services
- compute `requirementsMet`

This keeps the logic reusable and independently testable without overloading controller tests.

### Error handling

Do not invent a new error envelope. For an unknown customer, return plain `404`. The caller already treats non-2xx as `false`, so this remains fail-closed.

## Testing

Add contract-style integration tests in `pos-customer` following the current `BaseContractIntegrationTest` pattern.

Required coverage:

- active commercial party without credit hold returns `true`
- active person party returns `true`
- commercial party with `creditHold == true` returns `false`
- `INACTIVE`, `ON_HOLD`, and `MERGED` return `false`
- unknown customer id returns `404`
- unauthenticated request returns `401`

The tests should send:

- `X-User`
- `X-Authorities: crm:party:view`

and assert the raw boolean response body rather than a JSON object.

## Files

Expected touched files:

- create `pos-customer/src/main/java/com/positivity/customer/internal/controller/CustomerRequirementsController.java`
- create `pos-customer/src/main/java/com/positivity/customer/internal/service/CustomerRequirementsService.java`
- create `pos-customer/src/test/java/com/positivity/customer/contract/CustomerRequirementsContractBehaviorIT.java`

Potentially no changes are required outside `pos-customer`.

## Risks

### Duplicate lookup logic

The new service will mirror `CustomerController` resolution order. That duplication is acceptable for this narrow issue because the logic is simple and already stable.

### Raw boolean response

Some controller patterns in the repo return DTOs or envelopes. This endpoint must not follow that pattern because the existing caller contract depends on a raw boolean.

### Security test behavior

`pos-customer` contract tests rely on the gateway-header auth convention rather than `@WithMockUser`. The new tests should follow that same pattern exactly.

## Acceptance Mapping

- `GET /v1/customers/{id}/requirements-met` exists: covered by route test
- returns `true` for active customer without credit hold: covered
- returns `false` for `ON_HOLD`, `INACTIVE`, `MERGED`: covered
- returns `false` for commercial credit hold: covered
- returns `404` for unknown customer: covered
- enforces `crm:party:view`: covered by auth behavior tests
- works with zero `pos-workorder` client changes: preserved by raw boolean route contract
