# pos-inbound-mail

The inbound-mail edge of CAP:550 story S27 (#2520): vendor bills reach a shop by email at an
unguessable per-tenant address, this module is where that mail lands, and it hands each message to
`pos-accounting` as a command. It is a utility module under ADR-0044 §1 (amendment 2026-10-05) and
ADR-0070: it is called by `pos-accounting` and never calls a domain module.

This README covers **Part A** of four: the module and its wiring, the address registry and its API,
attachment staging, and the two payload records in `pos-domain-events`. Parts B to D add the receive
pipeline and the SES source (B), and pos-accounting's side (C, D).

## What it holds (S27 ruling 2)

The edge has its **own database** (`pos_inbound_mail_db`) with **operating state only**: the address
registry, one record per received message, rate-limit windows and staged attachments. It holds no business
data and no message body. Because its tables carry tenant rows under row-level security it adopts
`pos-tenancy-common` and is in `TenancyArchitectureTest.ADOPTED_MODULES`. (ADR-0070 and the ADR-0044
amendment call the edge "stateless"; S34 corrects that word to "holds no business data".)

| Table | Scope | Holds |
| --- | --- | --- |
| `inbound_address` | global (`tenancy-global-tables.txt`) | the registry: `address` (unique), owning `tenant_id` as data, `ACTIVE`/`RETIRED`; one `ACTIVE` per tenant |
| `inbound_message` | RLS | one row per message and tenant: `message_key` (unique per tenant), sender, status, reason |
| `rate_limit_window` | RLS | fixed clock-hour windows per address (used by Part B) |
| `staged_attachment` | RLS | ref (UUIDv7), file name as text, declared type, size, sha256 |
| `staged_attachment_content` | RLS | the bytes (`bytea`), behind the `AttachmentStaging` port (ruling 3) |
| `event_outbox`, `processed_events` | global | as every outbox module; used from Part B |

The address registry is global on purpose: the receive path resolves a recipient to its tenant before any
tenant is bound.

## API

`/inbound-mail/v1/**`, service to service only: the gateway has no route here (ADR-0014). The spec is
committed as [`openapi.yaml`](openapi.yaml) for documentation and validation only (#2428); it stays out of
the gateway's aggregated index and out of both SDKs. Regenerate it with
`scripts/generate-openapi.sh pos-inbound-mail`.

| Method and path | Purpose |
| --- | --- |
| `POST /addresses` | The tenant's `ACTIVE` address; issues one when none exists. Body is empty. 200 `{addressId, address, issuedAt}` |
| `POST /addresses/rotation` | Body `{requestId}`. Retires the `ACTIVE` address and issues a new one in one transaction. Idempotent on `requestId`: a replay returns the address that request issued |
| `GET /staged-attachments/{ref}` | The staged bytes: `application/octet-stream`, `Content-Length`, `X-Content-SHA256`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` |

| Header | Value |
| --- | --- |
| `X-Inbound-Mail-Secret` | `pos.inbound-mail.api-secret`, compared in constant time |
| `X-Tenant-Id` | the caller's bound tenant; there is **no default tenant** here |

### Error codes

| Status | Code | Meaning |
| --- | --- | --- |
| `401` | `INBOUND_MAIL_SECRET_MISSING` | The edge's own secret is blank, so every request is refused (fail closed) |
| `401` | `INVALID_INBOUND_MAIL_SECRET` | The `X-Inbound-Mail-Secret` header is missing or wrong |
| `401` | `TENANT_REQUIRED` | No `X-Tenant-Id` (from `TenantContextFilter`; it runs before the secret check) |
| `400` | `VALIDATION_FAILED` | Malformed body, for example a rotation without `requestId` |
| `404` | `STAGED_ATTACHMENT_NOT_FOUND` | The ref is unknown, another tenant's, collected or purged. **One identical body for all four** (control 6) |

The envelope is `ApiError` (ADR-0017) from `pos-web-common`. The module registers **no permissions**: the
secret is its only gate (ruling 11). It registers three event types at start-up and marks its three
endpoints `@EmitEvent`: `INBOUND_MAIL_ADDRESS_ISSUE`, `INBOUND_MAIL_ADDRESS_ROTATE` (write) and
`INBOUND_MAIL_STAGED_ATTACHMENT_GET` (fastRead).

## Addresses (ruling 8)

`bills-` plus 26 lower-case base32 characters (`a-z2-7`), each from five bits of `SecureRandom`, so 130
bits, then `@` and `pos.inbound-mail.domain`. The address is a **capability**: it is never derived from
the tenant, never copied to Kafka (events carry `receivingAddressId`), and never logged (only its id).
Lookup is exact after lower-casing, with no sub-addressing.

## Staging (ruling 3)

Staged bytes live in `staged_attachment_content` under the same RLS as their metadata, so another
tenant's ref reads as absent. `AttachmentStaging` is the port (`stage`, `fetch`, `release`,
`purgeStagedBefore`); `DbAttachmentStaging` is the v1 adapter. A per-tenant job (`StagingPurgeJob`,
`TenantIterator`, cron `pos.inbound-mail.staging-purge-cron`) deletes attachments older than
`pos.inbound-mail.staging-ttl`. It visits the tenants of the tenant registry, so a deployed run needs
`pos.tenancy.registry.mode=REMOTE` with `POS_TENANT_REGISTRY_API_SECRET` (or `pos.tenancy.tenants` listing
the tenants): the edge has no default tenant to fall back on.

## Payloads in `pos-domain-events` (ruling 6)

| Record | Type | Direction |
| --- | --- | --- |
| `BillIntakeEmailReceivedV1` | `accounting.bill-intake.email-received` v1 | command, edge to pos-accounting on `accounting.commands.v1` (published from Part B) |
| `BillIntakeEmailProcessedV1` | `accounting.bill-intake.email-processed` v1 | fact, pos-accounting to the edge on `accounting.events.v1` (consumed from Part B) |

The command carries `receivingAddressId`, never the address text. `fromAddress`, `subject` and file names
are CONFIDENTIAL (ADR-0072): they stay out of `toString()` and out of every log.

## Data handling (ADR-0072, ruling 12)

Never logged at INFO or above and never a metric tag: sender address, subject, file names. The full intake
address is never logged, only its id. No message body or attachment byte is logged anywhere. Message
records are kept 30 days (Part B purges them).

## Operating settings

| Setting | Env | Default | Meaning |
| --- | --- | --- | --- |
| `pos.inbound-mail.api-secret` | `POS_INBOUND_MAIL_API_SECRET` | blank | Shared secret; blank refuses every request |
| `pos.inbound-mail.source` | `POS_INBOUND_MAIL_SOURCE` | `none` | `none` receives nothing. It is the only source in Part A, and any other value fails start-up. `ses` arrives with Part B, behind the platform owner's sign-off |
| `pos.inbound-mail.domain` | `POS_INBOUND_MAIL_DOMAIN` | `mail.invalid` | Domain of every intake address. `mail.invalid` can never be a real mailbox |
| `pos.inbound-mail.staging-ttl` | `POS_INBOUND_MAIL_STAGING_TTL` | `P7D` | Staged attachments older than this are purged |
| `pos.inbound-mail.staging-purge-cron` | `POS_INBOUND_MAIL_STAGING_PURGE_CRON` | `0 15 3 * * *` | Nightly per-tenant purge |
| `pos.inbound-mail.max-attachment-bytes` | `POS_INBOUND_MAIL_MAX_ATTACHMENT_BYTES` | `26214400` | Largest attachment staged (S25's 25 MB limit) |
| `pos.tenancy.registry.*` | `POS_TENANCY_REGISTRY_MODE`, `_URL`, `POS_TENANT_REGISTRY_API_SECRET` | `REMOTE`, `http://pos-tenant:8080/internal/v1/tenants`, blank | Where the purge finds its tenants |

Scheduled jobs: `StagingPurgeJob` is per-tenant (`TenantIterator`); there is no `@PlatformScoped` job in
Part A.

## AWS resources (Part B, platform owner sign-off)

None in Part A: `AWS_EC2_METADATA_DISABLED=true` in Compose and no AWS SDK on the classpath. Part B will
list here the receipt rule, the S3 bucket (encrypted, 3-day lifecycle expiry, policy limited to
`ses.amazonaws.com` for this account and rule), the SNS topic, the SQS queue and its dead-letter queue, the
IAM principal (`GetObject`/`DeleteObject` on the bucket, `ReceiveMessage`/`DeleteMessage`/
`ChangeMessageVisibility` on the queue) and the MX record. Provisioning belongs to S34's deployment
runbook (rulings 9 and 10).

## Running

Local JVM (`dev` profile, H2, Eureka on `localhost:8761`):

```bash
cd pos-inbound-mail && ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Build and tests run on buildbox only (`CLAUDE.md`, "Backend Maven builds"). Integration tests (`*IT`) use
Testcontainers Postgres and run in `verify`.
