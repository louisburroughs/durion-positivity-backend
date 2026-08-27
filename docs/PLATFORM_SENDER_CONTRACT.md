# Platform Sender Contract (FI-2)

Contract between `pos-marketing` and the **shared platform sender** for campaign email/SMS
delivery and outcome feedback. Defines what `pos-marketing` builds against (durion#369,
plan decision O-1, stories #1149/#1150). The sender owns provider credentials, address
resolution, wire-level retries, and provider webhooks; `pos-marketing` owns orchestration
only — audience, consent/suppression gating, batching, per-recipient state.

## 1. Send API

`POST {pos.marketing.sender.base-url}/platform-sender/v1/messages`

Headers:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `X-Pos-Sender-Secret` | shared secret (`pos.marketing.sender.api-secret`); same pattern as `X-Pos-Events-Secret` |

Request body:

```json
{
  "messageId": "0198c2f0-…",          // campaignSendId — idempotency key
  "channel": "EMAIL",                  // EMAIL | SMS
  "recipientPartyId": "0198c2f0-…",
  "contactId": "0198c2f0-…",           // optional; the contact audience resolution picked
  "campaignCode": "SPRING24",          // sender-side metadata / provider tagging
  "subject": "…",                      // null for SMS
  "body": "…"                          // fully rendered; sender does no templating
}
```

Semantics:

- **Address resolution belongs to the sender.** `pos-marketing` never sends or stores a raw
  address; the sender resolves the recipient's address from `recipientPartyId`/`contactId`
  (via pos-people-contact) at delivery time.
- **Idempotency:** a replayed `messageId` MUST NOT produce a second delivery; the sender
  answers `200` with the original response instead of `202`.

Responses:

| Status | Meaning | Body |
| --- | --- | --- |
| `202` (or `200` on idempotent replay) | accepted for delivery | `{"providerMessageId": "…", "addressHash": "…"}` |
| `4xx` | permanent refusal (malformed, unknown party, no resolvable address) — caller marks the send `FAILED` | `ApiError` |
| `5xx` | transient — caller retries with backoff up to `pos.marketing.send.max-attempts` | `ApiError` |

`providerMessageId` is required on acceptance — it is the only correlation key for outcomes.
`addressHash` (SHA-256 of the normalized address) is optional; when present `pos-marketing`
stores it on the send record for bounce correlation.

## 2. Outcome feedback (Kafka)

Topic `sender.outcomes.v1` (`pos.marketing.kafka.sender-outcomes-topic`), standard domain
envelope (`eventId`, `eventType`, `payload`), at-least-once, keyed by `providerMessageId`.
The `occurredAt` timestamp (ISO-8601 instant) lives **inside `payload`**, next to
`providerMessageId`; a missing or malformed value makes pos-marketing fall back to its own
clock.

Event types and payload:

| `eventType` | Payload fields | Effect in pos-marketing |
| --- | --- | --- |
| `sender.message.delivered` | `providerMessageId`, `occurredAt` | send → `DELIVERED` |
| `sender.message.bounced` | `providerMessageId`, `reason`, `permanent` (bool, default `true`), `address`, `occurredAt` | send → `BOUNCED`; hard bounce → CRM suppression |
| `sender.message.complained` | `providerMessageId`, `reason`, `address`, `occurredAt` | send → `COMPLAINED`; always → CRM suppression |
| `sender.message.opened` | `providerMessageId`, `occurredAt` | stamps `openedAt` (first only) |
| `sender.message.clicked` | `providerMessageId`, `occurredAt` | stamps `clickedAt` (+ implies open) |

Open/click support is **optional** per channel/provider; consumers degrade gracefully when
these never arrive. `address` (raw, normalized) is REQUIRED on bounce/complaint so the
suppression hand-off can identify what to block; it is relayed to pos-customer and never
persisted by pos-marketing.

**Producer:** `sender.outcomes.v1` is produced entirely by the external **shared platform
sender** (the owner named above) — no module in this repository publishes it.
`pos-marketing`'s `DeliveryOutcomeListener` is the sole consumer. Because the producer lives
outside this repo, there is no in-repo test that exercises the wire format end-to-end; the
automated check that keeps the consumer honest against this table is
`PlatformSenderContractTest`
(`pos-marketing/src/test/java/com/positivity/marketing/internal/service/PlatformSenderContractTest.java`),
which drives `DeliveryOutcomeListener` with JSON fixtures built from the field names above and
pins each row's effect, including the `permanent`/`occurredAt` defaults (issue #1537).

## 3. Suppression feedback (pos-marketing → pos-customer)

On hard bounce or complaint, `pos-marketing` queues a command on `customer.commands.v1`
(transactional outbox, same transaction as the send-state change):

```json
{
  "commandType": "customer.suppression.add-requested",
  "eventId": "…",                      // reuses the sender outcome eventId → replay-safe
  "payload": {
    "channel": "EMAIL",
    "address": "jane@example.com",
    "partyId": "0198c2f0-…",
    "reason": "HARD_BOUNCE"            // HARD_BOUNCE | SPAM_COMPLAINT
  }
}
```

`pos-customer` applies it via `SuppressionService.add` (idempotent; stores only a normalized
hash + masked hint; source recorded as `SYSTEM`) and republishes
`customer.suppression.changed`, which flows back into pos-marketing's `ext_suppression`
replica — closing the loop so the next audience build and send-time re-check both exclude
the address.

## 4. Facts published by pos-marketing

On each applied terminal outcome, `marketing.events.v1` carries
`marketing.campaign.send.delivered|bounced|complained`
(`MarketingCampaignSendOutcomeV1`; aggregateId = recipient party).

## 5. Config reference (pos-marketing)

| Property | Default | Purpose |
| --- | --- | --- |
| `pos.marketing.send.transport` | `stub` | `stub` logs sends; `platform-sender` activates the adapter |
| `pos.marketing.sender.base-url` | `http://localhost:8085` | sender endpoint |
| `pos.marketing.sender.api-secret` | _(empty)_ | shared secret header |
| `pos.marketing.kafka.sender-outcomes-topic` | `sender.outcomes.v1` | outcome feed |
| `pos.marketing.kafka.customer-commands-topic` | `customer.commands.v1` | suppression hand-off |
