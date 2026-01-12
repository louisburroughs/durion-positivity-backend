# Architecture Decision Records (ADR)

## What is an ADR?

An Architecture Decision Record (ADR) is a document that captures an important architectural decision made along with its context and consequences. ADRs help preserve the reasoning behind significant technical choices, making it easier for current and future team members to understand why certain approaches were taken.

## When to Create an ADR

Create an ADR when making decisions about:
- System architecture and design patterns
- Technology choices (frameworks, databases, services)
- API contracts and data models
- Performance requirements and SLAs
- Security policies and implementations
- Integration patterns between services
- Data storage and retrieval strategies

## ADR Format

Each ADR should include:

1. **Title**: Brief description of the decision (e.g., "ADR 0001: Inventory Ledger ATP Computation")
2. **Status**: PROPOSED, ACCEPTED, DEPRECATED, SUPERSEDED
3. **Context**: The situation and problem that triggered the need for a decision
4. **Decision**: The choice that was made and key details
5. **Consequences**: Positive and negative outcomes of the decision, including mitigations
6. **References**: Links to issues, pull requests, and related documentation

## ADR Numbering

ADRs are numbered sequentially starting from 0001. When creating a new ADR, use the next available number.

## Current ADRs

| Number | Title                                      | Status   | Date       |
|--------|--------------------------------------------|----------|------------|
| 0001   | Inventory Ledger ATP Computation           | ACCEPTED | 2026-01-12 |

## Superseding ADRs

When a decision is superseded, update the old ADR's status to "SUPERSEDED BY ADR-XXXX" and create a new ADR explaining the new decision and why the change was made.

## Contributing

When adding a new ADR:
1. Copy an existing ADR as a template
2. Use the next sequential number
3. Fill in all required sections
4. Update this README with the new entry in the table above
5. Submit as part of your pull request
