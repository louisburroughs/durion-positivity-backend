# pos-openapi-validation

repository-wide OpenAPI validation for ADR-0042

## Commands

- `./mvnw -pl pos-openapi-validation -DskipTests=false test`
- `./mvnw -pl pos-openapi-validation -DskipTests=false -Dtest=OpenApiRepositoryValidationTest test`

## Policy

`src/test/resources/openapi/module-inventory.yaml` is the committed module policy inventory for repository validation. It maps each generated module spec to the enforcement mode that `pos-openapi-validation` applies when evaluating module-level output and the aggregate spec.

- `STRICT` &mdash; validation findings are blocking failures.
- `REPORT_ONLY` &mdash; validation findings are reported in REPORT mode and escalate to blocking failures in STRICT mode.
- `EXCEPTION` &mdash; the module is intentionally exempt from normal module-spec validation because it has distinct, documented semantics (for example, no normal module producer surface) and must carry a reason in the inventory.
- `EXCLUDED` &mdash; the module is outside the current validation scope and is skipped entirely until it is onboarded into a validating policy.
