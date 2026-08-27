---
title: Complete remaining ADR-0044 domain feeds and reconcile orphan Kafka flows
date: 2026-08-27
status: proposed
type: github-issue-draft
---

## Summary

Complete the asynchronous domain feeds still missing from the archived ADR-0044
target model, retire the remaining warranty-to-invoice synchronous dependency,
and resolve production Kafka topics that currently have only one side of their
producer/consumer contract.

## Context

The [archived 2026-07-16 model](domain-interaction-diagrams-2026-07-16.md)
contains the intended ADR-0044 event and command topology. The
[current implementation model](domain-interaction-diagrams.md) records only
source-evidenced communication present on 2026-08-27.

Of the archived target diagram's 26 explicit asynchronous connectors, 24 are
implemented:

- 21 of 22 fact/event connectors exist.
- Three of four command-or-event connectors exist. The archived
  people-contact-to-security connector is satisfied by the current
  `people-contact.events.v1` and `people-contact.manifest.v1` consumers.
- Planned customer, location, inventory, and most warranty replica migrations
  are present.

Two explicit target connectors remain absent. A third asynchronous flow is
required to retire the only warranty synchronous exception still present.

## Comparison Summary

| Archived target group                                                 | Target connectors | Present now | Status                                           |
| --------------------------------------------------------------------- | ----------------: | ----------: | ------------------------------------------------ |
| Customer facts to invoice, workorder, and shop-manager                |                 3 |           3 | Complete                                         |
| Location facts to inventory, people, invoice, and workorder           |                 4 |           4 | Complete                                         |
| People-contact facts to people, shop-manager, workorder, and security |                 4 |           4 | Complete; security consumes events and manifests |
| People facts to shop-manager and workorder                            |                 2 |           2 | Complete                                         |
| Workorder facts to customer, people, and accounting                   |                 3 |           2 | Missing accounting consumer                      |
| Inventory facts to catalog and workorder                              |                 2 |           2 | Complete                                         |
| Invoice facts to accounting and workorder                             |                 2 |           2 | Complete                                         |
| Vehicle facts to customer, shop-manager, and workorder                |                 3 |           3 | Complete                                         |
| Commands from accounting to invoice and workorder                     |                 2 |           1 | Missing accounting-to-invoice producer           |
| Commands from workorder to inventory                                  |                 1 |           1 | Complete                                         |
| **Total**                                                             |            **26** |      **24** | **Two explicit target connectors missing**       |

The archived dotted migration items compare as follows:

| Item | Archived intent                                                | Current result                                                                  |
| ---- | -------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| S1   | Replace invoice-to-customer lookup                             | Complete: customer events and manifest feed invoice                             |
| S2   | Replace invoice-to-location lookup                             | Complete: location events and manifest feed invoice                             |
| S3   | Remove workorder-to-shop-manager synchronous call              | Complete: no qualifying current synchronous edge                                |
| S4   | Complete location mirrors, including inventory                 | Complete: location events and manifest feed inventory                           |
| S5   | Add accounting command/result loops with invoice and workorder | Partial: each loop is missing one direction                                     |
| S6   | Retire warranty synchronous exceptions                         | Partial: only warranty-to-invoice remains, and it still covers reads and writes |

## Missing Target Feeds

### 1. Workorder facts to accounting

**Archived target:** `pos-workorder -> pos-accounting` on
`workorder.events.v1`.

**Current state:** `pos-workorder` creates facts through
[`WorkorderFactPublisher`](../pos-workorder/src/main/java/com/positivity/workorder/internal/service/WorkorderFactPublisher.java)
and publishes its outbox through
[`OutboxPublisher`](../pos-workorder/src/main/java/com/positivity/workorder/internal/config/OutboxPublisher.java),
but `pos-accounting` has no production listener for that topic. The reverse
command edge exists through
[`WorkorderCommandPublisher`](../pos-accounting/src/main/java/com/positivity/accounting/internal/config/WorkorderCommandPublisher.java).

**Gap:** the archived accounting/workorder command-result loop is one-way.
Accounting can request workorder behavior but cannot consume the intended
workorder result facts.

### 2. Accounting commands to invoice

**Archived target:** `pos-accounting -> pos-invoice` on
`invoice.commands.v1`.

**Current state:** `pos-invoice` has an
[`InvoiceCommandListener`](../pos-invoice/src/main/java/com/positivity/invoice/internal/config/InvoiceCommandListener.java),
but no production accounting publisher targets `invoice.commands.v1`. The
listener currently supports invoice generation and outbox replay only; it does
not define an accounting-specific command contract. The result direction does
exist through accounting's
[`InvoiceEventsListener`](../pos-accounting/src/main/java/com/positivity/accounting/internal/service/InvoiceEventsListener.java).

**Gap:** the archived accounting/invoice command-result loop is one-way.
Accounting consumes invoice facts but cannot issue the intended invoice
commands.

### 3. Warranty settlement commands to invoice

**Archived migration:** retire the scoped `pos-warranty -> pos-invoice`
synchronous exception in favor of event-fed behavior.

**Current state:** invoice reads are partly covered by the
[`InvoiceEventsListener`](../pos-warranty/src/main/java/com/positivity/warranty/internal/service/InvoiceEventsListener.java)
replica, but
[`InvoiceClientImpl`](../pos-warranty/src/main/java/com/positivity/warranty/internal/client/InvoiceClientImpl.java)
still performs synchronous invoice reads, adjustment creation, refund creation,
and refund/adjustment reconciliation. `DomainWallsTest` still grants the
module-level exception.

**Gap:** no warranty producer sends idempotent adjustment or refund commands to
`invoice.commands.v1`, and the invoice command listener has no corresponding
handlers. The exception cannot be removed until both write commands and
authoritative result facts cover the settlement workflow.

## Unmatched Producer and Consumer Flows

This list is separate from the archived target comparison. It covers concrete
production topic declarations found in the current source tree.

### Consumers With No In-Repository Producer

| Topic                | Consumer                                                                                                                                  | Finding                                                                                                                        | Required disposition                                                                                            |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| `people.commands.v1` | [`PeopleCommandListener`](../pos-people/src/main/java/com/positivity/people/internal/config/PeopleCommandListener.java)                   | Supports replay requests, but no production publisher sends them.                                                              | Add a manifest consumer that requests replay, or remove/disable the unused command surface.                     |
| `payment.cleared.v1` | [`PaymentEventListenerConfig`](../pos-accounting/src/main/java/com/positivity/accounting/internal/config/PaymentEventListenerConfig.java) | Legacy payment-cleared listener; no producer exists in this repository. Current invoice payment facts use `payment.events.v1`. | Migrate the required behavior to `payment.events.v1`, provide an owned producer, or remove the legacy listener. |
| `sender.outcomes.v1` | [`DeliveryOutcomeListener`](../pos-marketing/src/main/java/com/positivity/marketing/internal/service/DeliveryOutcomeListener.java)        | No producer exists in this repository. The configured platform sender is an external boundary.                                 | Document the external owner and add an integration/contract check proving it publishes the expected envelope.   |

### Producers With No Consumer

| Topic                | Producer                                                                                                        | Finding                                                                                    | Required disposition                                                                                                                                              |
| -------------------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `people.manifest.v1` | [`ManifestPublisher`](../pos-people/src/main/java/com/positivity/people/internal/config/ManifestPublisher.java) | Publishes reconciliation manifests, but no production `@KafkaListener` consumes the topic. | Add consumers for people replicas that compare manifests and publish `people.commands.v1` replay requests, or remove/disable both unused reconciliation surfaces. |

### Related Incomplete Reconciliation Flow

`catalog.manifest.v1` is not an orphan topic: catalog publishes it and inventory
consumes it. However,
[`CatalogManifestListener`](../pos-inventory/src/main/java/com/positivity/inventory/internal/service/CatalogManifestListener.java)
can only log drift because `pos-catalog` has no `catalog.commands.v1` listener.
The manifest detects divergence but cannot complete the ADR-0044 replay loop.

## Proposed Solution

1. Add an idempotent accounting consumer for the required
   `workorder.events.v1` result facts.
2. Define the accounting-owned invoice command use cases, publish them on
   `invoice.commands.v1`, and add idempotent handlers and result facts in
   `pos-invoice`.
3. Extend the invoice command contract for warranty adjustment and refund
   workflows. Replace `InvoiceClientImpl` write operations with commands and
   consume authoritative invoice result facts before removing the ADR-0044
   exception.
4. Complete or deliberately retire the people manifest/replay pair so
   `people.manifest.v1` and `people.commands.v1` are not independently inert.
5. Reconcile the legacy `payment.cleared.v1` listener with the current
   `payment.events.v1` contract.
6. Record and contract-test external ownership of `sender.outcomes.v1`.
7. Add catalog replay command handling, or document and enforce the operational
   mechanism that replaces automatic replay.

Do not create compatibility shims or duplicate old and new feeds. This platform
is pre-production; replace obsolete paths and remove retired configuration.

## Acceptance Criteria

- [ ] `pos-accounting` consumes the required `workorder.events.v1` result facts
      idempotently, with retry and DLQ behavior consistent with other domain
      listeners.
- [ ] Accounting's required invoice writes have an explicit, versioned command
      contract on `invoice.commands.v1`; `pos-invoice` handles each command
      idempotently and publishes authoritative result facts.
- [ ] Warranty adjustment and refund workflows no longer require synchronous
      invoice writes.
- [ ] Warranty invoice reads are served from event-fed replicas or otherwise
      removed from the workflow.
- [ ] The `pos-warranty -> pos-invoice` module exception is removed from
      `DomainWallsTest`, and `DomainWallsTest` passes.
- [ ] Every in-repository `*.events.v1`, `*.commands.v1`, and `*.manifest.v1`
      topic has at least one production producer and one production consumer,
      unless an explicit external owner is documented and contract-tested.
- [ ] `people.manifest.v1` and `people.commands.v1` form a working
      detect-and-replay loop, or both unused surfaces are removed.
- [ ] `payment.cleared.v1` has an owned producer or its accounting listener and
      configuration are removed after equivalent `payment.events.v1` behavior
      is verified.
- [ ] `sender.outcomes.v1` has a documented external owner and an automated
      contract or integration test.
- [ ] Catalog replica drift can trigger an owned replay path, or the accepted
      replacement procedure is documented and monitored.
- [ ] Provider/consumer contract tests cover command envelopes, fact envelopes,
      idempotency keys, replay windows, retries, and DLQ routing.
- [ ] The current domain interaction diagrams and edge catalogs are regenerated
      from the resulting implementation.

## Verification

Run at minimum:

```bash
./mvnw -pl pos-accounting,pos-invoice,pos-warranty,pos-people,pos-inventory,pos-catalog -am test
./mvnw -pl pos-archunit -am -Dtest=DomainWallsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Also run a production-source topic inventory that fails when an internal topic
has producers without consumers or consumers without producers. External topics
must be allowlisted with an owner and contract-test reference, not silently
ignored.

## Out of Scope

- Audit/control-plane `@EmitEvent` registration with `pos-event-receiver`.
- DLQ topics generated by listener error handling.
- Utility HTTP calls permitted by ADR-0044.
- Planned domain interactions not present in the archived target diagram or in
  executable production source.
