# pos-openapi-validation

`pos-openapi-validation` is the repository's OpenAPI policy module. It does not provide runtime behavior; it packages validator code and tests that read generated OpenAPI files from the worktree and fail Maven when the repository violates the ADR-0042 rules.

The reusable validator and policy classes live under `src/main/java`. The Maven test phase remains the enforcement entrypoint — the module is not started as an application.

## How it works

The repository validation flow is:

1. Each producer module writes its generated spec to `<module>/openapi.yaml`.
2. The gateway aggregate spec is written to `pos-api-gateway/docs/openapi-aggregate.yaml`.
3. `OpenApiRepositoryValidationTest` loads the committed policy inventory from `src/test/resources/openapi/module-inventory.yaml`.
4. `OpenApiRepositoryValidator` (in `src/main/java`) orchestrates `OpenApiModuleValidator` and `OpenApiAggregateValidator` to validate each module in scope and the aggregate spec.

### What is checked

`OpenApiModuleValidator` checks module specs for:

- missing spec files
- malformed spec files
- missing `paths`
- operations missing `summary`
- operations missing `description`

`OpenApiAnnotationDepthValidator` additionally checks, for modules whose `annotationDepth` is not `EXEMPT`, that each operation meets the ADR-0042 §1 and §3 depth rules fixed in `docs/OPENAPI_DESCRIPTION_STANDARD.md`:

- descriptions of 4–8 sentences opening with a primary-action sentence
- the six remaining §1 elements, detected by their canonical lead-ins (`Use this tool ...`, `Preconditions: ...`, `Required inputs: ...`, `Emits ...` / `No events are emitted`, `Returns <code> when ...`, and negative guidance such as `do not use ...`)
- request bodies carrying a `description`, an explicit `required`, and at least one example

`OpenApiErrorResponseSchemaValidator` additionally checks, for modules whose `errorSchema` is not `EXEMPT`, that every 4xx/5xx response in the generated spec references the canonical `ApiError` envelope (ADR-0017 §3, issue #1720). An `@ApiResponse` for an error status that omits `content`/`schema` does not produce an empty schema — springdoc fills it in by inference, from the endpoint's own success type or from a `@ControllerAdvice` handler's return type, so the published contract tells generated clients that the error body is the 200 DTO. A response with no `content` at all is not a finding: a genuinely bodiless error is a legitimate contract and springdoc emits no schema for it. This check reads the generated spec rather than the annotations, because the inference is only visible there — and because that spec is what the Angular SDK is generated from.

`OpenApiAggregateValidator` checks the aggregate spec for:

- duplicate YAML keys in the aggregate file
- unresolved `$ref` targets

## Policy inventory

`src/test/resources/openapi/module-inventory.yaml` is the source of truth for module enforcement mode.

| Mode | Meaning |
| --- | --- |
| `STRICT` | Findings are blocking now. |
| `REPORT_ONLY` | Findings are collected as baseline gaps in report mode and become blocking in strict mode. |
| `EXCEPTION` | The module is intentionally skipped and must include a reason. |
| `EXCLUDED` | The module is outside the current rollout scope and is skipped entirely. |

A module that is absent from the inventory is not validated at all — the validator never looks at a spec it was not told about, so an unregistered module can ship an `openapi.yaml` that does not parse and CI stays green. `scripts/check-openapi-inventory-drift.sh` guards against that: it fails when a module with a committed `openapi.yaml` has no inventory entry, and when an inventory entry names something that is no longer a reactor module. CI runs it on every build.

Description depth is a second, independent dimension on the same entry:

| `annotationDepth` | Meaning |
| --- | --- |
| `STRICT` | Depth findings are blocking now. |
| `REPORT_ONLY` | Depth findings are reported and become blocking under `-Dopenapi.validation.mode=STRICT`. This is the default when the key is absent. |
| `EXEMPT` | Depth is not checked; requires an `annotationDepthReason`. |

Error-envelope conformance is a third, independent dimension on the same entry:

| `errorSchema` | Meaning |
| --- | --- |
| `STRICT` | Error-schema findings are blocking now. |
| `REPORT_ONLY` | Findings are reported and become blocking under `-Dopenapi.validation.mode=STRICT`. This is the default when the key is absent. |
| `EXEMPT` | Error response schemas are not checked; requires an `errorSchemaReason`. |

`pos-vehicle-inventory` is the reference conversion at `errorSchema: STRICT` (#1720). Every other module stays `REPORT_ONLY` until its error annotations carry an explicit `content = @Content(schema = @Schema(implementation = ApiError.class))`, so a default-mode run stays green while `-Dopenapi.validation.mode=STRICT` reports the full fleet gap — 701 mis-typed error responses across 18 modules at the time the check was added. The fix always belongs in the controller annotation, never in the inventory entry.

`mode`, `annotationDepth` and `errorSchema` are deliberately separate: every module is already `STRICT` on summary/description *presence*, while ADR-0042's depth requirement was met by no module in the fleet when it was introduced (#1263). `pos-tax` and `pos-supplier` are the reference conversions at `annotationDepth: STRICT`; every other module stays `REPORT_ONLY` until its descriptions are rewritten, so a default-mode run stays green while `-Dopenapi.validation.mode=STRICT` reports the full fleet gap.

When adding a new spec-producing module, register it at `STRICT`. That may surface real defects in the spec; the fix belongs in the controller annotations the spec is generated from, not in the inventory entry. If the module cannot be made `STRICT`-clean immediately, `REPORT_ONLY` is still better than absence.

## Commands

Run the full module test suite:

```bash
./mvnw -pl pos-openapi-validation -DskipTests=false test
```

Run just the repository-wide validation:

```bash
./mvnw -pl pos-openapi-validation -DskipTests=false -Dtest=OpenApiRepositoryValidationTest test
```

Promote `REPORT_ONLY` findings to blocking failures:

```bash
./mvnw -pl pos-openapi-validation -DskipTests=false \
  -Dopenapi.validation.mode=STRICT \
  -Dtest=OpenApiRepositoryValidationTest test
```

Run the common validator subset used during troubleshooting:

```bash
./scripts/test-openapi-validation.sh
```

## Where to find the results

Results show up in normal Maven/Surefire test output.

- If the command ends with `BUILD SUCCESS`, there were no blocking validation issues for the mode you ran.
- If the command ends with `BUILD FAILURE`, look at the failing test output for `OpenApiRepositoryValidationTest` or one of the validator tests.
- For CI or saved artifacts, the same failures are written under `pos-openapi-validation/target/surefire-reports/`.

## How to interpret the results

Validation messages are source-attributed so you can tell whether the problem came from a module spec or from the aggregate spec.

Typical message formats:

- `pos-location GET /v1/locations: missing summary`
- `pos-catalog POST /v1/products: 409 response body is ProductDto, not ApiError (ADR-0017 §3; a schema-less @ApiResponse lets springdoc infer the wrong type)`
- `pos-documents GET /v1/documents: missing description`
- `pos-order: spec file not found: ...`
- `pos-order: spec file could not be parsed: ...`
- `aggregate /v1/documents: unresolved ref ../missing-module/openapi.yaml#/paths/~1v1~1documents`
- `aggregate: duplicate key detected: ...`

Interpret them as follows:

- `pos-... METHOD /path: ...` means a module-level operation is missing required metadata.
- `pos-...: spec file ...` means the expected generated module spec is missing or malformed.
- `aggregate ...` means the gateway aggregate spec points at an invalid or duplicated path reference.

## Important behavior note

The default validation mode is `REPORT`. In that mode, `REPORT_ONLY` modules do not fail the build. A passing run in default mode therefore means:

- all `STRICT` module rules passed
- aggregate validation passed
- any remaining findings are limited to modules still marked `REPORT_ONLY`

If you want to see whether the repository is clean enough to make those baseline gaps blocking, rerun the repository test with `-Dopenapi.validation.mode=STRICT`.
