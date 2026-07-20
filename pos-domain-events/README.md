# pos-domain-events

Shared, versioned contract library for cross-module domain and command events (ADR-0044). Every
message on a `{domain}.events.v1` / `{domain}.commands.v1` topic is a
[`DomainEventEnvelope`](src/main/java/com/positivity/domainevents/DomainEventEnvelope.java) wrapping
one versioned payload record from this module, so producers and consumers compile against the same
contract. Payloads are immutable Java records validated in their compact constructors; changes within
a schema version must be **additive and nullable** (ADR-0044 §3). A breaking change requires a new
payload version (`…V2`) and a new topic version (`.v2`), dual-published during migration.

## Settlement contract (accounting parity, plan `plan-odoo-parity-pos-accounting.md` §7, story F1a)

Normalized, processor-agnostic settlement reconciliation contract. The payment/settlement adapter
(pos-invoice `internal.settlement`, issue #962) translates any processor's payout model into these
records; pos-accounting (issue #963) consumes them. pos-accounting contains **zero
processor-specific code** (decision D-5).

| Payload | Topic | Kafka key | Retention | Owner → Consumer |
| --- | --- | --- | --- | --- |
| [`SettlementReportedV1`](src/main/java/com/positivity/domainevents/payment/SettlementReportedV1.java) | `payment.events.v1` | `settlementId` | delete (fact stream) | payment adapter → pos-accounting |
| [`SettlementProviderConfigV1`](src/main/java/com/positivity/domainevents/payment/SettlementProviderConfigV1.java) | `payment.settlement-config.v1` | `providerCode` | **compacted** (last-writer-wins) | payment adapter → pos-accounting `ext_payment_settlement_config` replica |

### `SettlementReportedV1`

One fact per provider settlement (payout). `eventType = "payment.settlement.reported"`, schema
version 1. Frozen invariants (decision D-10):

- **Header balance:** `grossAmount == feeAmount + netAmount` (validated, scale-independent).
- **Single currency per settlement:** the header `currency` (ISO-4217) governs every line; there is
  no per-line currency. The adapter emits one event per payout currency (decision D-14).
- **`lineType` enum (frozen):** `CHARGE, REFUND, CHARGEBACK, FEE, ADJUSTMENT, RESERVE_HOLD,
  RESERVE_RELEASE, PAYOUT_CORRECTION`. `PAYOUT` is intentionally absent — the header *is* the payout;
  a payout line would double-count. Consumers must route an unmappable `lineType` to suspense, never
  mis-post.
- **Correlation refs by type:** `providerLineRef` + `paymentReference` are **required** on
  `CHARGE`/`REFUND`/`CHARGEBACK` (they carry the platform token the processor echoed —
  `ReceivablePayment.sourceEventId` for AR, `APPayment.paymentRef` for AP, matched on gross, decision
  D-11) and **optional** on all other types.
- **Nullable per-line `feeAmount`/`netAmount`:** netted-per-line and header-only providers omit them;
  matching always uses line `grossAmount`, never net (decision D-12).
- Carries its own `eventId`/`schemaVersion` (mirroring the envelope) so a persisted settlement row is
  self-describing for replay/idempotency, plus an optional `extension` map for provider-raw fields
  (never business-critical).

### `SettlementProviderConfigV1`

Per-provider matching configuration, authored and owned by the payment service and delivered as a
**compacted** event keyed by `providerCode` (last-writer-wins). Consumed into a read-only
`ext_payment_settlement_config` replica in pos-accounting rather than embedded per settlement event —
config is a slowly-changing fact with one owner (ADR-0044 §3, decision D-9). Because the topic is
compacted and keyed by `providerCode`, each record is the provider's full current configuration; there
is no delta/partial update. Fields: `matchReferenceField` (`PAYMENT_REFERENCE` | `PROVIDER_LINE_REF`),
non-negative `amountTolerance`, `feeRepresentation` (`SEPARATE_LINES` | `NETTED_PER_LINE` |
`HEADER_ONLY`, decision D-12), provider identity (`providerCode` + optional `providerName`), and an
optional `extension` map. The replica tolerates unknown fields so config may evolve additively.

### Additive-evolution rules (ADR-0044 §3)

- Add only nullable fields within schema version 1; never rename, retype, remove, or change the
  meaning of an existing field, and never add a non-nullable field without a default.
- The frozen `LineType` / `FeeRepresentation` / `MatchReferenceField` enums may gain new constants
  additively; consumers must treat an unknown constant defensively (settlement lines → suspense).
- A change that would break existing consumers requires `SettlementReportedV1V2` on
  `payment.events.v2`, dual-published during the migration window.
