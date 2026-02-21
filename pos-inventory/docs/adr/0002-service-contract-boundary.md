# ADR-0002: Service Contract Boundary in `pos-inventory`

- Status: Accepted
- Date: 2026-02-21
- Owners: `pos-inventory` maintainers

## Context

`pos-inventory` follows a modular boundary where only `com.positivity.inventory.service` is intended as the public API for other modules. Some concrete Spring services were placed directly in `service`, which blurred the boundary between public contracts and internal implementation.

This made it harder to:

- understand what is safe for other modules to depend on,
- evolve implementation details without leaking internals,
- enforce architecture consistently across the module.

## Decision

`com.positivity.inventory.service` contains interfaces only.

Concrete Spring-managed classes must live in `com.positivity.inventory.internal.service` and implement the interfaces from `service`.

Specifically:

- Controllers and internal components depend on `service` interfaces.
- Implementations are annotated with Spring stereotypes in `internal.service`.
- New business services must be added as an interface + implementation pair.

## Enforcement

- ArchUnit rule in `pos-inventory/src/test/java/com/positivity/inventory/ArchitectureTest.java` requires classes in `com.positivity.inventory.service..` to be interfaces.
- Internal package cycle checks remain enforced for `com.positivity.inventory.internal.*`.
- Code review standard: reject concrete service classes under `com.positivity.inventory.service`.

## Consequences

- Clear API surface for cross-module use.
- Implementation details remain encapsulated and easier to refactor.
- Small increase in boilerplate (interface + implementation), accepted as a tradeoff for modularity and maintainability.
