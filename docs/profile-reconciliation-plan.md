# Backend Profile Reconciliation Plan

## Summary

Complete and harden the in-progress profile migration so backend modules consistently use:

- `openapi` (Maven profile for OpenAPI generation only; renamed from `local`)
- `dev` (local developer runtime, H2 + minimal laptop config)
- `alpha` (EC2 runtime, near-prod)
- `prod` (production runtime, currently rough/placeholder)

## Key Implementation Changes

### 1. Finalize profile taxonomy and migration mapping

- Enforce mapping: `local -> dev` and `preprod -> alpha` everywhere in runtime/profile wiring.
- Keep non-environment overlays as-is: `test`, `local-kafka`, `standalone`.
- Treat `openapi` as Maven-only build profile, not a runtime environment profile.

### 2. Reconcile Maven OpenAPI profiles across modules

- Ensure every module that generates `openapi.yaml` uses `<id>openapi</id>` in `pom.xml` and starts with `--spring.profiles.active=dev`.
- Keep OpenAPI generation behavior consistent (`spring-boot:start/stop` + `springdoc-openapi:generate`).
- Verify `scripts/generate-openapi.sh` remains aligned with default profile `openapi`.

### 3. Reconcile Spring runtime profile config across services

- Standardize `application.yml` as env-driven base (no hardcoded `spring.profiles.active`).
- Keep `application-dev.yml` as local H2 + local Eureka defaults.
- Keep `application-alpha.yml` and `application-prod.yml` as near-prod/prod overlays (minimal now, env-first).
- Remove legacy leftovers tied to old profile strategy (`application.old`, obsolete `application.properties` where superseded).

### 4. Operations/docs/script cleanup

- Update `.env.example` default `SPRING_PROFILES_ACTIVE` from `local` to `dev` and update profile comment text.
- Update remaining script/readme language still saying "local profile" when it now means `dev`.
- Keep existing `alpha` defaults for MCP/Ollama compose wiring and `@Profile("alpha")` usage in mcp-server classes.

### 5. Document the final canonical profile contract

- Add a short profile matrix section that defines purpose, activation method, and intended environments for `openapi`, `dev`, `alpha`, `prod`.
- Include migration note: old names (`local`, `preprod`) are retired.

## Public Interfaces / Operational Contract Changes

- Runtime profile names used by operators/developers are now `dev`, `alpha`, `prod` (old `local` and `preprod` removed).
- Maven command contract for OpenAPI generation is `-Popenapi`.
- Environment template default profile becomes `SPRING_PROFILES_ACTIVE=dev`.

## Test Plan

1. Legacy-name regression check
   - Run a repo-wide grep (excluding `target/`) for `application-local.yml`, `application-preprod.yml`, `spring.profiles.active=local`, `SPRING_PROFILES_ACTIVE=local`, `@Profile("preprod")`, and `-Plocal`.
   - Expect zero matches in active source/docs/scripts (except intentional historical notes if any).
2. Maven profile consistency check
   - For each OpenAPI-generating module, confirm `-Popenapi` activates successfully (`help:active-profiles`) and that no module still depends on `local` profile ID.
3. OpenAPI generation smoke
   - Run `scripts/generate-openapi.sh` for a representative subset (`pos-api-gateway`, `pos-order`, `pos-security-service`) and confirm `openapi.yaml` generation succeeds.
4. Runtime profile smoke
   - Start representative services with `dev` and verify boot succeeds with H2/eureka-local assumptions.
   - Start `pos-mcp-server` with `alpha` and validate profile-gated beans load as expected.
5. Documentation/ops verification
   - Validate examples in README/scripts/docs use the canonical profile names and commands.

## Assumptions and Defaults

- Cleanup is in scope with profile reconciliation (legacy artifacts removed now, not deferred).
- Plan document location is `docs/profile-reconciliation-plan.md`.
- No backward-compat aliasing for `local`/`preprod`; migration is explicit and forward-only.
- `prod` remains intentionally rough and env-driven until production environment specifics are finalized.
