---
type: Index
title: Backend Documentation Index
description: Indexes the operating documents that stay beside the build, and says where each relocated platform document now lives in the durion repo.
status: current
---

# Documentation

This directory holds the operating documents for `durion-positivity-backend` — the ones whose value
depends on sitting beside the build they describe. Platform knowledge that outlives any one repo
(architecture, contracts, standards, decision records, audits) lives in the sibling `durion` repo
and is reachable through `../../durion/knowledge-catalog/`.

## What lives here

| Document | Description |
| ---------- | ------------- |
| [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) | Toolchain and version baseline, profile matrix, the three OpenAPI generation methods, `API Artifacts Sync`, SonarCloud measurement, CI build invariants |
| [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md) | Operations, monitoring, RBAC framework, permission registration, troubleshooting |
| [DATA_SEED_STRATEGY.md](DATA_SEED_STRATEGY.md) | Which datasets may enter through Flyway and which must enter through the owning service's API |
| [runbooks/accelerated-alpha-deployment.md](runbooks/accelerated-alpha-deployment.md) | Deploying and verifying the accelerated-clock alpha stack |
| [runbooks/flyway-baseline-reset.md](runbooks/flyway-baseline-reset.md) | Method for re-flattening a module's Flyway history |
| `permissions-report.yaml` | **Generated** by `scripts/generate-permissions.sh`; rewritten by `API Artifacts Sync`. Do not hand-edit |
| `sonarqube-remediation-inventory.csv` | Derived from SonarCloud plus hand-added status; regeneration recipe in `DEVELOPMENT_GUIDE.md` → "Code Quality Measurement" |
| `sql/` | One-off alpha data repairs, re-runnable after a reset |

## What moved to `durion`, and where

| Subject | Now at |
| ------- | ------ |
| Runtime topology: Docker, ports, observability, PostgreSQL | `../../durion/docs/architecture/BACKEND_ARCHITECTURE_GUIDE.md` |
| `ApiError` envelope contract | `../../durion/docs/architecture/api/ERROR_ENVELOPE.md` |
| OpenAPI operation description standard | `../../durion/docs/architecture/api/OPENAPI_DESCRIPTION_STANDARD.md` |
| Internal transport, service discovery, gateway exceptions | `../../durion/docs/architecture/INTERNAL_TRANSPORT_AND_SERVICE_DISCOVERY.md` |
| Domain interaction model | `../../durion/docs/architecture/DOMAIN_INTERACTION_MODEL.md` |
| Test coverage policy | `../../durion/docs/architecture/TEST_COVERAGE_POLICY.md` |
| Tenancy schema conventions | `../../durion/docs/architecture/deployment/TENANCY_SCHEMA.md` |
| Entity relationship migration ledger | `../../durion/docs/architecture/deployment/data-migration/ENTITY_RELATIONSHIP_MIGRATION_LEDGER.md` |
| RBAC audit, location-scope spike | `../../durion/domains/security/` |
| Platform sender wire contract | `../../durion/domains/positivity/PLATFORM_SENDER_CONTRACT.md` |
| UUID v7 strategy, clock ownership, availability vs on-hand | ADR-0013, ADR-0024, ADR-0066 in `../../durion/docs/adr/` |

**Per-module error codes** now live in each module's own `README.md`, next to the controllers that
emit them. The envelope contract stays in `durion`; the code tables stay here.

Executed plans, delivered PRDs and superseded assessments were removed rather than moved — git
history holds them, and the decisions they produced are in the ADRs.

## Module-specific documentation

A module may carry its own `docs/` directory, for example `pos-inventory/docs/` and
`pos-catalog/docs/`.

## Architecture Decision Records

ADRs are centralized in `../../durion/docs/adr/`. Compliance is mandatory — see `AGENTS.md`.

## Contributing to documentation

1. Ask first whether the document is **platform knowledge** or **operating mechanics**. Platform
   knowledge goes to `durion`; mechanics stay here, beside what they describe.
2. Architecture, infrastructure, or contract change → update the matching document in
   `../../durion/docs/architecture/`.
3. Development workflow → [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md).
4. Operations or security → [OPERATIONS_RUNBOOK.md](OPERATIONS_RUNBOOK.md).
5. Module-specific → `<module>/docs/` or the module `README.md`.
6. Architecture decision → add an ADR in `../../durion/docs/adr/`.

Every document added under `docs/` here, or under the platform areas in `durion`, is indexed into
`../../durion/knowledge-catalog/platform/` on the next catalog run. Give it YAML frontmatter with
`type`, `title`, `description` and `status`, and make its first sentence a standalone claim about
what it governs — that sentence becomes its catalog entry.
