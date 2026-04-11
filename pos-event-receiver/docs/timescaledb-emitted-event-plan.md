# TimescaleDB for `emitted_event` — Implementation Plan

## Objective

Convert the `emitted_event` table to a TimescaleDB hypertable to gain automatic time-based partitioning, built-in compression, and optimized time-range queries without adding a separate database container.

## Current State

- **Database**: `pos_event_receiver_db` on the shared `postgres:16-alpine` container (`postgres-positivity`)
- **Table**: `emitted_event` — simple heap table with UUID primary key, no partitioning
- **Schema** (from `V1__baseline_event_receiver_schema.sql`):

```sql
CREATE TABLE IF NOT EXISTS emitted_event (
  event_id UUID PRIMARY KEY,
  id VARCHAR(255),
  api_version VARCHAR(255),
  timestamp BIGINT,
  elapsed_ms BIGINT,
  published_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_emitted_event_id ON emitted_event (id);
```

- **JPA Entity**: `EmittedEvent.java` — `event_id` is the `@Id` with `@UUIDv7Id` generation, `published_at` is `Instant`

## Approach

Swap the Docker Compose Postgres image from `postgres:16-alpine` to `timescale/timescaledb:latest-pg16`. TimescaleDB is a PostgreSQL extension (not a fork), so all existing databases, schemas, and drivers remain unchanged. The extension is enabled per-database, so only `pos_event_receiver_db` is affected.

## Changes Required

### 1. Docker Compose — Image Swap

**File**: `docker-compose.yml`

```diff
  postgres:
-   image: postgres:16-alpine
+   image: timescale/timescaledb:latest-pg16
    container_name: postgres-positivity
```

No other Docker changes needed. The TimescaleDB image is a drop-in replacement that ships the standard `postgres` binary plus the `timescaledb` extension.

### 2. Flyway Migration — Hypertable Conversion

**File**: `pos-event-receiver/src/main/resources/db/migration/V2__timescaledb_emitted_event.sql`

```sql
-- Enable TimescaleDB extension in this database
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- TimescaleDB requires the partitioning column in the primary key.
-- Replace the single-column PK with a composite PK including published_at.
ALTER TABLE emitted_event DROP CONSTRAINT emitted_event_pkey;
ALTER TABLE emitted_event ADD PRIMARY KEY (event_id, published_at);

-- Convert to hypertable, partitioned by published_at.
-- migrate_data => true moves existing rows into chunks.
SELECT create_hypertable('emitted_event', by_range('published_at'), migrate_data => true);

-- 1-day chunks strike a good balance for daily ingestion volumes.
SELECT set_chunk_time_interval('emitted_event', INTERVAL '1 day');

-- Enable native columnar compression on chunks older than 7 days.
ALTER TABLE emitted_event SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'id',
  timescaledb.compress_orderby = 'published_at DESC'
);
SELECT add_compression_policy('emitted_event', INTERVAL '7 days');
```

### 3. JPA Entity — No Code Changes

The `EmittedEvent` entity requires no modifications. JPA operates on logical rows; the hypertable partitioning is transparent. The existing `@Id` on `event_id` continues to work — the composite PK is a physical storage constraint, not a logical JPA concern.

### 4. Continuous Aggregate (Post-Migration)

After the hypertable is live, add a continuous aggregate for dashboard-style queries:

```sql
CREATE MATERIALIZED VIEW emitted_event_hourly
WITH (timescaledb.continuous) AS
SELECT
  time_bucket('1 hour', published_at) AS bucket,
  id AS event_type,
  COUNT(*) AS event_count,
  AVG(elapsed_ms) AS avg_elapsed_ms,
  PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY elapsed_ms) AS p95_elapsed_ms,
  PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY elapsed_ms) AS p99_elapsed_ms
FROM emitted_event
GROUP BY bucket, id;

SELECT add_continuous_aggregate_policy('emitted_event_hourly',
  start_offset => INTERVAL '3 hours',
  end_offset => INTERVAL '1 hour',
  schedule_interval => INTERVAL '1 hour');
```

### 5. Migrate `EventSummaryController` Queries to Continuous Aggregate

Once the `emitted_event_hourly` continuous aggregate is live (step 4 above), the summary endpoints should read from the pre-aggregated view instead of scanning the raw hypertable. This eliminates full-table GROUP BY on every request.

#### 5a. New Read-Only Entity — `EmittedEventHourly`

**File**: `pos-event-receiver/src/main/java/com/positivity/poseventreceiver/internal/entity/EmittedEventHourly.java`

```java
package com.positivity.poseventreceiver.internal.entity;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read-only JPA entity mapped to the {@code emitted_event_hourly}
 * TimescaleDB continuous aggregate.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "emitted_event_hourly")
@IdClass(EmittedEventHourlyId.class)
public class EmittedEventHourly {

    @Id
    @Column(name = "bucket")
    private Instant bucket;

    @Id
    @Column(name = "event_type")
    private String eventType;

    @Column(name = "event_count")
    private long eventCount;

    @Column(name = "avg_elapsed_ms")
    private double avgElapsedMs;

    @Column(name = "p95_elapsed_ms")
    private double p95ElapsedMs;

    @Column(name = "p99_elapsed_ms")
    private double p99ElapsedMs;
}
```

#### 5b. Composite ID Class

**File**: `pos-event-receiver/src/main/java/com/positivity/poseventreceiver/internal/entity/EmittedEventHourlyId.java`

```java
package com.positivity.poseventreceiver.internal.entity;

import java.io.Serializable;
import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@EqualsAndHashCode
public class EmittedEventHourlyId implements Serializable {
    private Instant bucket;
    private String eventType;
}
```

#### 5c. New Repository — `EmittedEventHourlyRepository`

**File**: `pos-event-receiver/src/main/java/com/positivity/poseventreceiver/internal/repository/EmittedEventHourlyRepository.java`

```java
package com.positivity.poseventreceiver.internal.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.poseventreceiver.internal.entity.EmittedEventHourly;
import com.positivity.poseventreceiver.internal.entity.EmittedEventHourlyId;

@Repository
public interface EmittedEventHourlyRepository
    extends JpaRepository<EmittedEventHourly, EmittedEventHourlyId> {

  @Query("""
      SELECT h.eventType, SUM(h.eventCount)
      FROM EmittedEventHourly h
      WHERE h.bucket >= :since
      GROUP BY h.eventType
      ORDER BY SUM(h.eventCount) DESC
      """)
  List<Object[]> summarizeSince(@Param("since") Instant since);
}
```

#### 5d. Update `EventSummaryServiceImpl`

Replace the `EmittedEventRepository` dependency with `EmittedEventHourlyRepository` so all three summary endpoints read from the continuous aggregate:

```java
@Slf4j
@RequiredArgsConstructor
@Service
public class EventSummaryServiceImpl implements EventSummaryService {

  private final EmittedEventHourlyRepository emittedEventHourlyRepository;
  private final Clock clock;

  @Override
  public @NonNull List<EventSummaryResponse> getLastHourSummary() {
    log.info("Fetching event summary for the last hour");
    return getSummary(Duration.ofHours(1));
  }

  @Override
  public @NonNull List<EventSummaryResponse> getLastDaySummary() {
    log.info("Fetching event summary for the last day");
    return getSummary(Duration.ofDays(1));
  }

  @Override
  public @NonNull List<EventSummaryResponse> getLastWeekSummary() {
    log.info("Fetching event summary for the last week");
    return getSummary(Duration.ofDays(7));
  }

  private List<EventSummaryResponse> getSummary(Duration window) {
    Instant since = Instant.now(clock).minus(window);
    List<Object[]> results = emittedEventHourlyRepository.summarizeSince(since);
    return results.stream()
        .map(row -> new EventSummaryResponse((String) row[0], (Long) row[1]))
        .toList();
  }
}
```

The controller itself (`EventSummaryController.java`) requires no changes — it already delegates to `EventSummaryService`.

## Implementation Steps

| Step | Action | Affected File |
| ---- | ------ | ------------- |
| 1 | Swap Postgres image to TimescaleDB | `docker-compose.yml` |
| 2 | Recreate the Postgres container | `docker compose up -d postgres` |
| 3 | Add V2 migration SQL (hypertable) | `pos-event-receiver/.../V2__timescaledb_emitted_event.sql` |
| 4 | Add V3 migration SQL (continuous aggregate) | `pos-event-receiver/.../V3__continuous_aggregate.sql` |
| 5 | Start pos-event-receiver (Flyway runs V2 + V3) | Application startup |
| 6 | Verify hypertable and aggregate | `SELECT * FROM timescaledb_information.hypertables;` |
| 7 | Add `EmittedEventHourly` entity + ID class | `internal/entity/EmittedEventHourly.java`, `EmittedEventHourlyId.java` |
| 8 | Add `EmittedEventHourlyRepository` | `internal/repository/EmittedEventHourlyRepository.java` |
| 9 | Update `EventSummaryServiceImpl` to use aggregate | `internal/services/EventSummaryServiceImpl.java` |
| 10 | Update existing tests for new repository dependency | `EventSummaryServiceImplTest.java` |

## Recommendations

1. **Retention policy** — Add a data retention policy to automatically drop chunks older than a configurable threshold. Start with 90 days and adjust based on storage and query patterns:

   ```sql
   SELECT add_retention_policy('emitted_event', INTERVAL '90 days');
   ```

2. **Chunk interval tuning** — The plan uses 1-day chunks. If daily event volume exceeds ~10M rows, consider smaller intervals (e.g., `INTERVAL '6 hours'`). If volume is under 100K/day, increase to `INTERVAL '7 days'` to reduce chunk overhead.

3. **Compression segmentby** — The plan segments compression by `id` (event type code). This optimizes queries that filter by event type. If queries more commonly filter by `api_version`, add it to `compress_segmentby`.

4. **Index strategy** — After hypertable conversion, the existing `idx_emitted_event_id` index is automatically replicated per-chunk. Consider adding a composite index if common queries filter on both `id` and time range:

   ```sql
   CREATE INDEX idx_emitted_event_id_time ON emitted_event (id, published_at DESC);
   ```

5. **Pin the TimescaleDB image version** — Use a pinned tag (e.g., `timescale/timescaledb:2.17.2-pg16`) in production rather than `latest-pg16` to prevent unexpected extension upgrades during container rebuilds.

6. **Volume data compatibility** — The existing `postgres-data` Docker volume is fully compatible with the TimescaleDB image. No data migration or volume wipe is needed when swapping images. However, take a backup before the swap as standard practice.

7. **Monitor compression ratio** — After compression kicks in (7 days post-migration), verify savings:

   ```sql
   SELECT * FROM hypertable_compression_stats('emitted_event');
   ```

   Typical compression ratios for event data range from 10:1 to 20:1.
