# OpenAPI Validation Module Design

## Problem

ADR-0042 needs machine-checkable enforcement for generated OpenAPI metadata across modules and the aggregate gateway spec. The current rollout baseline identifies blocking gaps, but the existing shell and Python tooling is better suited to spec generation and cleanup than to acting as the long-term source of truth for repository-wide enforcement.

## Proposed Approach

Introduce a dedicated Maven module, `pos-openapi-validation`, that owns ADR-0042 policy enforcement in Java. Existing generation scripts remain available to produce `openapi.yaml` files, but build pass/fail behavior moves into Maven tests so validation is deterministic in local development and CI. Reusable validator and policy code should live in `src/main/java`; repository-wide enforcement remains in tests.

## Architecture

### New module

Create `pos-openapi-validation` as a lightweight validation module whose responsibility is repository-wide OpenAPI policy enforcement.

- It should not host runtime application behavior.
- It should parse generated module specs and the aggregate spec from disk.
- It should fail with source-attributed, operation-specific messages.
- It should keep reusable validation logic out of `src/test/java` so the code has normal source boundaries and can be tested as production code.

This keeps repository policy out of runtime gateway and MCP code, and it avoids overloading `pos-coverage-aggregate`, which is already dedicated to coverage aggregation.

### Responsibilities

`pos-openapi-validation` should own:

- module inventory and exception policy for ADR-0042 rollout scope
- OpenAPI parsing and validation logic
- aggregate-spec validation against source-module expectations
- fixture-driven tests for policy behavior
- repository-wide enforcement tests that exercise the production validator classes against the real worktree layout

Existing tooling keeps its narrower responsibilities:

- `scripts/generate-openapi.sh`: generate module and aggregate specs
- `scripts/sanitize-openapi.py`: sanitize springdoc output as a producer-side helper

## Data Flow

1. Each module continues generating `openapi.yaml` through its existing `openapi` Maven profile.
2. The aggregate spec continues to be written to `pos-api-gateway/docs/openapi-aggregate.yaml`.
3. A repository validation test in `pos-openapi-validation` loads a committed inventory describing which modules are:
   - required producers
   - documented exceptions
   - excluded from the current enforcement scope
4. Production validator classes in `src/main/java` parse each required module spec, record concrete failures, and then validate the aggregate spec against that module inventory.
5. Validation output stays attributable to the source module even when the aggregate spec is the artifact being checked.

## Failure Behavior

Validation should fail the build deterministically for any of the following:

- missing spec file for a required module
- missing `paths` section for a required producer
- missing operation `summary`
- missing operation `description`
- unresolved aggregate references
- duplicate aggregate paths
- undocumented exception states

Failure messages should include the module, HTTP method, and path whenever applicable, for example:

`pos-location GET /v1/locations/{locationId}: missing description`

If rollout staging is needed, it should be expressed in committed validation policy rather than ad hoc script behavior so `report` versus `strict` remains reproducible in Maven and CI.

The mode split should remain policy-driven:

- `STRICT` modules always block on findings.
- `REPORT_ONLY` modules are reported in report mode and promoted to blocking in strict mode.
- `EXCEPTION` and `EXCLUDED` modules are skipped according to committed inventory rules, with exceptions requiring documented reasons.

## Testing Strategy

### Fixture-driven validator tests

Add focused tests under `pos-openapi-validation` using small YAML fixtures for:

- missing summary
- missing description
- missing `paths`
- documented exceptions
- duplicate aggregate paths
- broken aggregate `$ref` targets
- source-attribution message formatting

These tests should exercise validator rules without starting services. The validators under test should come from `src/main/java`, with test fixtures and assertions staying in `src/test/java`.

### Repository integration coverage

Add integration-style tests that validate generated repository specs already present in the worktree or produced in a preparatory Maven step. The validator itself should remain file-based and should not boot services. These tests are the repository enforcement layer and should be the primary pass/fail entrypoint used by developers and CI.

The target command surface becomes:

`./mvnw -pl pos-openapi-validation -am test`

Generation remains a separate preparatory step using the existing OpenAPI generation flow.

## Scope Notes

- This design is intentionally focused on validation tooling and enforcement, not on remediating individual modules yet.
- `pos-api-gateway` remains the aggregate artifact producer, not the enforcement owner.
- `pos-mcp-server` remains a consumer of aggregate output and should benefit from stronger upstream guarantees rather than embedding repository policy.
- The implementation target is the validation module itself: move reusable code into main sources, keep enforcement in tests, and avoid adding a new runtime CLI or service surface unless a later requirement demands it.

## Success Criteria

- ADR-0042 enforcement is owned by a dedicated Java validation module.
- Validation failures are machine-checkable and source-attributed.
- Aggregate validation no longer hides source-module metadata issues.
- Local and CI enforcement run through Maven without relying on shell-script-only policy decisions.
