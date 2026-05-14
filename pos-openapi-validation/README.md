# pos-openapi-validation

`pos-openapi-validation` is the repository's OpenAPI policy module. It does not provide runtime behavior; it packages validator code and tests that read generated OpenAPI files from the worktree and fail Maven when the repository violates the ADR-0042 rules.

Most of the real logic lives under `src/test/java`. The module is meant to be run as tests, not started as an application.

## How it works

The repository validation flow is:

1. Each producer module writes its generated spec to `<module>/openapi.yaml`.
2. The gateway aggregate spec is written to `pos-api-gateway/docs/openapi-aggregate.yaml`.
3. `OpenApiRepositoryValidationTest` loads the committed policy inventory from `src/test/resources/openapi/module-inventory.yaml`.
4. `OpenApiRepositoryValidator` validates each module in scope with `OpenApiModuleValidator`, then validates the aggregate spec with `OpenApiAggregateValidator`.

### What is checked

`OpenApiModuleValidator` checks module specs for:

- missing spec files
- malformed spec files
- missing `paths`
- operations missing `summary`
- operations missing `description`

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
