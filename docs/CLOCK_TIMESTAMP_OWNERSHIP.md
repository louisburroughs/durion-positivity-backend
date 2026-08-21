# Clock and Timestamp Ownership

Every `created_at` and `updated_at` this platform writes must come from the injected
application `Clock`. Under the `accelerated` profile that clock is the shared converging
`ScaledClock` supplied by `pos-events`, so a value taken from anywhere else lands a year
away from every other value in the same row set.

This document records where each timestamp comes from and which reads are deliberately
left on database or wall time.

## The three legitimate mechanisms

1. **Spring Data JPA auditing.** `@EntityListeners(AuditingEntityListener.class)` with
   `@CreatedDate` / `@LastModifiedDate`, fed by each module's
   `@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")` and a
   `DateTimeProvider` returning `Instant.now(clock)`. This is the default; prefer it.
2. **An injected `Clock` in the writing service.** `Instant.now(clock)` where `clock` is
   the shared bean. Used where a timestamp is domain data rather than an audit column —
   an outbox `created_at`, a replica's `updated_at`.
3. **`TimeSource`** for JPA lifecycle callbacks that cannot inject a bean. Under the
   accelerated profile `TimeSource` throws on a read taken before the clock is bound,
   rather than silently returning wall time.

Hibernate's `@CreationTimestamp`, `@UpdateTimestamp` and `@CurrentTimestamp` are **not**
legitimate mechanisms. They are produced by Hibernate's own generator and never consult
the Spring `Clock` bean. `ArchitectureTests.productionCodeShouldNotUseHibernateTimestampGenerators`
fails the build on any new use.

## Entities without audit annotations

These carry `createdAt`/`updatedAt` fields with no `@CreatedDate`/`@LastModifiedDate`.
Each is correct as it stands, because the writing code supplies the injected clock.

| Module | Entity | Unannotated field | Why |
| --- | --- | --- | --- |
| `pos-catalog` | `ExtInventoryAvailabilityReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-catalog` | `ExtProductLeadTimeReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-catalog` | `OutboxEvent` | createdAt | outbox — written by the module's outbox writer |
| `pos-customer` | `ExtOrganizationPostalAddress` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-customer` | `ExtPersonReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-customer` | `ExtVehicle` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-customer` | `ExtVehicleCarePreference` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-customer` | `OutboxEvent` | createdAt | outbox — written by the module's outbox writer |
| `pos-customer` | `ServiceHistory` | createdAt | service-written |
| `pos-customer` | `SuppressionEntry` | createdAt | service-written |
| `pos-image` | `ImageContentEntity` | createdAt | service-written |
| `pos-image` | `ImageEntity` | createdAt/updatedAt | service-written |
| `pos-marketing` | `CampaignSend` | updatedAt | service-written |
| `pos-marketing` | `SegmentReplica` | updatedAt | service-written |
| `pos-marketing` | `SuppressionReplica` | updatedAt | service-written |
| `pos-security-service` | `ExtCustomerPersonIdentity` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-security-service` | `ExtPersonReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-security-service` | `OutboxEvent` | createdAt | outbox — written by the module's outbox writer |
| `pos-shop-manager` | `ExtCustomerPartyReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-shop-manager` | `ExtPersonReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-shop-manager` | `ExtStaffingAssignmentReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-shop-manager` | `ExtVehicleReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-vehicle-inventory` | `OutboxEvent` | createdAt | outbox — written by the module's outbox writer |
| `pos-workorder` | `ExtBillingRulesReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtCustomerPartyReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtInventoryAvailabilityReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtInvoiceReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtLocationReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtPersonReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtPickListReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtPickTaskReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtStaffingAssignmentReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtUserLinkReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `ExtVehicleReplica` | updatedAt | replica — copies the source aggregate's timestamp |
| `pos-workorder` | `OutboxEvent` | createdAt | outbox — written by the module's outbox writer |

Replica entities (`Ext*`) hold a copy of another module's aggregate. Their `updated_at`
records when this module last applied an event for that aggregate, and the applying
listener stamps it with `Instant.now(clock)`. Adding `@LastModifiedDate` would be
equivalent, not corrective, so the annotations are deliberately absent.

`OutboxEvent.created_at` is set by each module's outbox writer, also from the injected
clock. The outbox row's timestamp orders publication, so it must move with application
time exactly like the aggregate it describes.

## What keeps this true

Three ArchUnit rules in `pos-archunit`, applied across every module the suite scans:

- `productionCodeShouldNotUseNoArgNowCalls` — no `Instant.now()`, `LocalDate.now()`,
  `LocalDateTime.now()`, `LocalTime.now()`, `OffsetDateTime.now()` or
  `ZonedDateTime.now()` without an explicit `Clock`.
- `productionCodeShouldNotCallClockSystemUtcOutsideSharedTimeInfrastructure` — no
  `Clock.systemUTC()` or `Clock.systemDefaultZone()` outside `com.positivity.time` and
  `com.positivity.events`.
- `productionCodeShouldNotUseHibernateTimestampGenerators` — no Hibernate generators.

Together these mean any timestamp a service writes must have been given a `Clock`, and
the only `Clock` available is the shared bean.

SQL is covered separately, because bytecode rules cannot see it:

- `queryAnnotationsShouldNotWriteDatabaseTime` reads `@Query` annotation values.
- `sourceLevelSqlShouldNotWriteDatabaseTime` scans string literals in every module's
  `src/main/java`, flattening `+`-joined chains so a multi-line native query is checked
  as one statement. This catches `entityManager.createNativeQuery(...)` and SQL built
  from concatenated constants.

Both reject `now()`, `current_timestamp`, `clock_timestamp()`, `localtimestamp` and
`current_date` in the write half of an `INSERT`/`UPDATE`. A `WHERE` comparison is allowed:
with application time trailing wall time, a read-side filter against database time widens
the active set rather than hiding rows.

Exceptions live in `DATABASE_TIME_WRITE_ALLOWLIST`, keyed by fully-qualified method name.
Every entry must carry a justification — a separate test fails on a blank one.

## Deliberate database-clock and wall-clock reads

| Where | Why it stays |
| --- | --- |
| `supplier_schedule_lease.leased_until`, `last_heartbeat_at`, `last_run_started_at` | Lease liveness is a real-time question: whether another process is alive now. Accelerating these would expire live leases in a fraction of their duration and let two runs process one binding. The same table's `updated_at` **is** bound from the application clock. |
| `DEFAULT NOW()` columns in `pos-security-service`, `pos-bulk-loader`, `pos-mcp-server`, `pos-catalog` | The default fires only when an INSERT omits the column, which Hibernate never does for a mapped field. |
| JPQL `CURRENT_TIMESTAMP` / `CURRENT_DATE` in `GLMappingRepository`, `RoleAssignmentRepository` | Read-side effective-date filters; see the WHERE-clause reasoning above. |
| Kafka record timestamps, log timestamps | Transport metadata, not business data. |
| Scheduler trigger times (`@Scheduled` fixed delays) | Poll cadence is a real-time concern. Polling frequency is never multiplied by clock scale; only the *comparisons* a job makes use the injected clock. |

After an accelerated run, `deployment/alpha/verify-accelerated-timestamps.sql` checks the
result: no timestamp after wall time, none before the run's virtual anchor, and no
`updated_at` preceding its own `created_at`.
