# Domain Interaction Diagrams Refresh Design

**Date:** 2026-08-27

**Status:** Approved for planning

## Goal

Supersede `docs/domain-interaction-diagrams.md` with an exhaustive, source-evidenced
model of the backend's current module communication topology while preserving the
existing content as `docs/domain-interaction-diagrams-2026-07-16.md`. The new
canonical version contains no planned or target-state edges.

## Scope

- Model the current implementation only.
- Include every proven synchronous, event, and command edge between modules.
- Include external-provider edges when a backend module calls the provider directly.
- Include startup-infrastructure edges, grouping repeated registrations only when
  source evidence proves the same pattern for every listed module.
- Use ADR-0044 and `DomainWallsTest` to explain policy and scoped synchronous
  exceptions, not to infer unimplemented communication.
- Rename the existing document to `docs/domain-interaction-diagrams-2026-07-16.md`
  so its historical content remains intact.
- Create the latest document at `docs/domain-interaction-diagrams.md` so existing
  links continue to resolve to the canonical current model.
- Cross-link the historical and current versions.

## Evidence Rules

An edge is included only when current Java source or runtime configuration proves
both endpoints and the transport. Examples include a concrete REST client target,
Kafka producer topic, Kafka listener topic, or external-provider base URL.

`DomainWallsTest` proves that a synchronous domain edge is permitted. Permission
alone does not prove that the edge is implemented. ADR-0044 provides policy and
rationale but does not create implementation edges.

Planned, inferred, disabled, and historical edges are omitted. Ambiguous edges are
listed in caveats rather than drawn.

## Document Structure

The canonical current document contains:

1. Scope, evidence date, source hierarchy, supersession notice, and a link to the
   archived 2026-07-16 version.
2. A legend defining synchronous, fact/event, command, result, manifest, and
   external-provider connectors.
3. A synchronous-communication Mermaid diagram covering utility calls, external
   providers, and implemented ADR-0044 exceptions.
4. A domain-facts-and-replicas Mermaid diagram covering fact topics, replica
   consumers, manifests, reconciliation commands, and DLQ paths where proven.
5. A commands-and-results Mermaid diagram covering implemented command flows and
   result-event return paths.
6. An exhaustive edge catalog with stable edge IDs, source, destination,
   transport or topic, purpose, and source-file evidence.
7. Caveats describing grouped edges and communication surfaces that cannot be
   established from current evidence.

## Diagram Boundaries

Each diagram is organized by transport rather than by lifecycle target state.
Modules keep a consistent identifier and classification across all three diagrams.
Utility, domain, library, infrastructure, and external nodes use distinct styles.

Cross-domain synchronous calls are visually emphasized because they require an
ADR-0044 exception. The permanent `pos-warranty` to `pos-invoice` settlement edge,
the `pos-order` to `pos-invoice` checkout/cancellation edge, and class-scoped
supplier stock inquiry edges are shown only when their implementation is confirmed.

Repeated topic consumers may share one producer-to-consumers fan-out when the edge
catalog retains one row per producer/consumer relationship. This keeps the diagrams
readable without weakening exhaustiveness.

## Data Flow

The source inventory is reduced into normalized edges with these fields:

- Edge ID
- Origin module
- Target module or external provider
- Transport category
- Topic, service ID, or endpoint category
- Communication purpose
- Concrete source evidence
- ADR-0044 exception reference when applicable

The normalized edge inventory drives both Mermaid connectors and the edge catalog.
Every diagram edge must map to at least one catalog row, and every catalog row must
appear in exactly one transport diagram.

## Error Handling

- Omit edges whose origin, target, or transport cannot be proven.
- Record ambiguous or configuration-dependent communication in caveats.
- Do not turn topic-name similarity into a producer/consumer relationship without
  matching source evidence.
- Do not treat the audit-only `@EmitEvent` pipeline as domain data transport.
- Do not treat shared-library dependencies as runtime communication edges.

## Validation

- Check the Markdown diff for whitespace and structural errors.
- Check that all relative file links resolve.
- Parse or render all Mermaid blocks when a local Mermaid tool is available.
- Compare every synchronous domain edge with `DomainWallsTest` exceptions.
- Run `./mvnw -pl pos-archunit -am -Dtest=DomainWallsTest test`.
- Confirm the document contains no planned or target-state connectors.

## Acceptance Criteria

- The unchanged 2026-07-16 content is preserved at
  `docs/domain-interaction-diagrams-2026-07-16.md` with a historical-version
  notice and link to the canonical document.
- `docs/domain-interaction-diagrams.md` explicitly supersedes the archived
  2026-07-16 version and carries an evidence date of 2026-08-27.
- The document models current implementation only.
- Communication is split into synchronous, fact/event, and command/result diagrams.
- Every proven edge appears in the edge catalog with concrete source evidence.
- Every catalog edge appears in exactly one Mermaid diagram.
- ADR-0044 exceptions match current enforcement and implementation.
- Relative links resolve and the focused ArchUnit test passes.
