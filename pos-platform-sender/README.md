# pos-platform-sender

The shared platform sender (FI-2): delivers rendered campaign email through **Amazon SES** and SMS
through **AWS End User Messaging**, and relays what the providers report back to
`sender.outcomes.v1`. The wire contract is `../durion/domains/positivity/PLATFORM_SENDER_CONTRACT.md`;
conventions and commands: `AGENTS.md` at the repository root.

`pos-marketing` owns orchestration (audience, consent and suppression gating, batching, per-recipient
state). This module owns everything that touches a provider: addresses, credentials, delivery and
provider events. It is a domain module under ADR-0044 §1 with one class-scoped synchronous caller
(amendment 2026-10-03): `pos-marketing`'s `PlatformSenderClient`, the only client `DomainWallsTest`
lets reach it. It calls no domain module itself.

## Send API

`POST /platform-sender/v1/messages` (operationId `sendPlatformMessage`), service-to-service only (the gateway has
no route here). The spec is committed as [`openapi.yaml`](openapi.yaml) for documentation and
validation only (#2428): it stays out of the gateway's aggregated index, which lists routed APIs,
and out of both SDKs, whose generators name their modules one by one. Regenerate it with
`scripts/generate-openapi.sh pos-platform-sender`.

| Header | Value |
| --- | --- |
| `X-Pos-Sender-Secret` | `pos.platform-sender.api-secret`; a blank secret refuses every request (401) |
| `X-Tenant-Id` | the caller's bound tenant; `TenantContextFilter` binds it (else the transitional default) |

| Status | Code | Meaning |
| --- | --- | --- |
| `202` | | Accepted; body `{providerMessageId, addressHash}` |
| `200` | | Replay of an accepted `messageId`; same body, nothing re-sent |
| `400` | `VALIDATION_FAILED` | Malformed request |
| `401` | `PLATFORM_SENDER_SECRET_MISSING`, `INVALID_PLATFORM_SENDER_SECRET`, `TENANT_REQUIRED` | Not authenticated |
| `422` | `SUBJECT_REQUIRED` | An `EMAIL` message without a subject |
| `422` | `RECIPIENT_NOT_REPLICATED` | Neither `contactId` nor `recipientPartyId` is a person party in the local replica |
| `422` | `NO_CONTACT_POINT` | The person has no email (or no `PHONE_MOBILE` number) |
| `422` | `UNDELIVERABLE_ADDRESS` | The stored value is not a valid email, or has no E.164 form |
| `422` | provider error code (`MessageRejected`, `ConflictException`, ...) | The provider refused the message for good (a replay answers the same) |
| `503` | `SEND_IN_FLIGHT` | Another request holds this `messageId`, or an earlier attempt never settled |
| `503` | provider error code, `PROVIDER_UNREACHABLE` | Throttled, provider 5xx, or a failure before the request left; nothing was delivered |
| `503` | `PROVIDER_NO_RESPONSE` | No provider answer after the request left (a read timeout): it may have been delivered, so the claim stays `PENDING` and every replay answers `SEND_IN_FLIGHT` |

Idempotency: the `messageId` is claimed in `sent_message` before the provider call and settled after
it. The SES and SMS clients make one attempt (no SDK retries): a provider call is retried only by the
caller, and only when it is known not to have been delivered. A crash between the provider accepting
and the row settling, or a provider call that got no answer, leaves the claim `PENDING`, which
answers `503` to every replay: the caller's bounded retry then records the send as failed although it
may have been delivered. That is the side FI-2's "no second delivery" rule errs on; neither provider
accepts a client token.

## Address resolution (ADR-0044 R3 replicas)

`contactId` (the person party pos-customer's consent decision named), else `recipientPartyId`:

1. `ext_customer_person_party` (party → pos-people-contact person), from `customer.events.v1`
   (`customer.party.updated` for `PERSON` parties, `customer.party.deleted`).
2. `ext_people_contact_person` (person → email, mobile phone), from `people-contact.events.v1`
   (`people-contact.person.updated`, `people-contact.person.deleted`). The primary `EMAIL` and the
   primary `PHONE_MOBILE` contact point are kept (else the first of each type).

Emails are trimmed and lowercased; phone numbers become E.164 (a 10-digit number gets
`sms.default-country-code`, a number with the code and 10 digits gets a `+`, anything else is
undeliverable). `addressHash` is SHA-256 (lowercase hex) of the normalized address, the same function
pos-customer's suppression list uses.

Both replicas reconcile against `customer.manifest.v1` and `people-contact.manifest.v1`
(`ReplicaManifestListener`); a drifted window triggers the owner's `outbox.replay-requested`
command. **Bootstrap:** a new replica fills from the owners' outbox replay, which reaches back
`outbox.replay.max-lookback` (`P30D` by default) in each owner. A person or party last changed
before that is `RECIPIENT_NOT_REPLICATED` until it changes again, or until the owner re-emits it
(pos-customer: `POST /v1/crm/accounts/facts/replay`; pos-people-contact has no current-state
re-emit yet).

## Provider outcomes

Every provider message is tagged with `pos-tenant-id`, `pos-message-id` and `pos-campaign` (SES
`EmailTags`, SMS `Context`). The SES and SMS configuration sets publish events to SNS, SNS to one SQS
queue, and `OutcomeQueuePoller` long-polls that queue:

| Provider event | `sender.outcomes.v1` | `permanent` |
| --- | --- | --- |
| SES `Delivery`, SMS `TEXT_DELIVERED` (or a final `TEXT_SUCCESSFUL`) | `sender.message.delivered` | |
| SES `Bounce` `Permanent`, SMS `TEXT_INVALID` | `sender.message.bounced` | `true` (CRM suppression) |
| SES `Bounce` `Transient`/`Undetermined`, `Reject`, `Rendering Failure`; SMS `TEXT_UNREACHABLE`, `TEXT_CARRIER_UNREACHABLE`, `TEXT_BLOCKED`, `TEXT_CARRIER_BLOCKED`, `TEXT_SPAM`, `TEXT_TTL_EXPIRED`, `TEXT_UNKNOWN`, `TEXT_INVALID_MESSAGE` | `sender.message.bounced` | `false` |
| SES `Complaint` | `sender.message.complained` | |
| SES `Open` / `Click` | `sender.message.opened` / `sender.message.clicked` | |
| SES `Send`, `DeliveryDelay`; SMS `TEXT_QUEUED`, `TEXT_PENDING`, `TEXT_SENT` | ignored (deleted) | |

Every bounce and complaint carries `address`, which FI-2 §2 requires for the suppression hand-off:
the recipient the event names, else the message's destination (`mail.destination` for SES,
`destinationPhoneNumber` for SMS). An event that names neither is treated as unusable.

Each outcome goes through the outbox under the tagged tenant (Kafka header `tenantId`, record key
`providerMessageId`, envelope aggregate `messageId`), with a `processed_events` mark keyed by the
SNS message id so a redelivered queue message is never relayed twice. A message the poller cannot
use (not JSON, not tagged by this sender) stays on the queue for its redrive policy to dead-letter.

The SMS event names follow AWS End User Messaging's published event schema; confirm the mapping
against a real event from the sandbox before going live (`ProviderOutcomeMapperTest` pins the
fixtures it was built from).

## Configuration (`pos.platform-sender.*`)

| Property | Env | Default | Purpose |
| --- | --- | --- | --- |
| `api-secret` | `POS_PLATFORM_SENDER_API_SECRET` | blank (refuse all) | Shared secret for `X-Pos-Sender-Secret` |
| `transport` | `POS_PLATFORM_SENDER_TRANSPORT` | `log` | `log` accepts and logs, contacting nobody; `aws` sends for real |
| `aws.region` | `POS_PLATFORM_SENDER_AWS_REGION` | `us-east-1` | Region of SES, End User Messaging and SQS |
| `email.from-address` | `POS_PLATFORM_SENDER_EMAIL_FROM` | blank | Verified SES identity, e.g. `Durion <no-reply@durionpos.org>` |
| `email.configuration-set` | `POS_PLATFORM_SENDER_EMAIL_CONFIGURATION_SET` | blank | SES configuration set with the SNS event destination |
| `sms.origination-identity` | `POS_PLATFORM_SENDER_SMS_ORIGINATION_IDENTITY` | blank | Phone number, pool or sender id; blank lets AWS choose |
| `sms.configuration-set` | `POS_PLATFORM_SENDER_SMS_CONFIGURATION_SET` | blank | End User Messaging configuration set with the SNS event destination |
| `sms.message-type` | `POS_PLATFORM_SENDER_SMS_MESSAGE_TYPE` | `PROMOTIONAL` | `TRANSACTIONAL` or `PROMOTIONAL` |
| `sms.default-country-code` | `POS_PLATFORM_SENDER_SMS_DEFAULT_COUNTRY_CODE` | `1` | Code for stored national numbers |
| `outcomes.enabled` | `POS_PLATFORM_SENDER_OUTCOMES_ENABLED` | `false` | Run the queue poll (needs `kafka.enabled`) |
| `outcomes.queue-url` | `POS_PLATFORM_SENDER_OUTCOMES_QUEUE_URL` | blank | The SQS queue the SNS topic(s) deliver to |
| `kafka.enabled` | `POS_PLATFORM_SENDER_KAFKA_ENABLED` | `false` | Replica consumers and the outbox drain |

Credentials come from the AWS default provider chain: on the alpha host, the EC2 instance profile
(`durion-alpha-instance-profile`; the container reaches IMDSv2, which needs a hop limit of 2). The
role needs `ses:SendEmail`, `sms-voice:SendTextMessage`, and `sqs:ReceiveMessage` /
`sqs:DeleteMessage` on the outcomes queue.

## AWS setup (one-time)

1. **SES:** verify the sending domain (Route 53 DKIM), request production access (the sandbox only
   delivers to verified addresses), create a configuration set with an SNS event destination for
   `DELIVERY`, `BOUNCE`, `COMPLAINT`, `REJECT`, `RENDERING_FAILURE`, `OPEN` and `CLICK`.
2. **End User Messaging SMS:** obtain an origination identity (US: a toll-free number with
   verification, or 10DLC brand and campaign registration, which takes weeks), create a
   configuration set with an SNS event destination for `TEXT_ALL`. Opt-out keywords (STOP) are
   handled by AWS; a send to an opted-out number is refused (`422 ConflictException`).
3. **SQS:** one queue subscribed to the SNS topic(s), with a redrive policy to a dead-letter queue.

## Multitenancy (ADR-0062)

Adopted from the start: `sent_message` and both replicas extend `TenantScopedEntity` under row-level
security; `event_outbox` and `processed_events` are `@TenantGlobal`
(`src/main/resources/db/tenancy-global-tables.txt`). Requests bind the tenant from `X-Tenant-Id`, the
Kafka consumers from the `tenantId` record header, and the outcome poll (`@PlatformScoped`) from the
`pos-tenant-id` tag on each provider event. The pool connects as `pos_app`; Flyway alone uses the owner
credential.

Proof: `TenantIsolationIT` and `TenancySchemaConformanceIT` on Testcontainers Postgres
(`./mvnw -pl pos-platform-sender -am verify`).

## Development

```bash
./mvnw -pl pos-platform-sender -am test
./mvnw -pl pos-platform-sender -am verify   # + Testcontainers ITs (Docker)
```

## Reconciliation manifest replay requests (#2452)

A manifest listener that finds drift sends the owner's `outbox.replay-requested` command through
`OutboxReplayRequests`, which waits up to 30s for the broker's acknowledgement. A request that cannot
be handed to Kafka, that the broker rejects, or that is not acknowledged in time propagates to `KafkaErrorHandlingConfig`, which retries the manifest with backoff and then dead-letters it to `{topic}.dlq`.
Swallowing it would lose the repair for good, because each owner publishes a window's manifest once
and no later manifest covers that window again. Redelivery is safe: a manifest writes nothing, the
comparison only reads, and the replay command is keyed by window start. A manifest that does not parse
is still dropped.
