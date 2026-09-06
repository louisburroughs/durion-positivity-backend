package com.positivity.mcp.internal.config;

import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backfills the vector embedding of every {@code mcp_tool} row that has none.
 *
 * <p>Gate 3 (G3.1): discovery persists openapi rows without embeddings; this fills them in so
 * semantic tool selection can see them.
 *
 * <p>#1818: this used to be an {@code ApplicationRunner} that embedded one tool at a time on the
 * main thread before the application reported ready. On alpha (2026-09-06) 884 rows had lost their
 * embeddings (#1819) and the container answered 503 on {@code /actuator/health} for twenty minutes
 * while it re-embedded them at ~1.2 s each — longer than the deploy's health wait, so the deploy
 * failed although the container was fine. The backfill now starts on {@link ApplicationReadyEvent}
 * on its own thread, so readiness never waits for it, and it embeds in batches so a large backlog
 * costs one model round-trip per batch rather than per tool. A row without an embedding is
 * invisible to tool selection (both candidate queries filter on {@code embedding IS NOT NULL})
 * until the backfill reaches it, so the backfill still wants to be quick — just not on the
 * readiness path.
 *
 * <p>The batch size must fit the embedding client's timeout ({@code OLLAMA_EMBEDDING_TIMEOUT},
 * 30 s): at alpha's ~1.2 s per description on CPU, 8 descriptions is ~10 s per call with margin;
 * 32 would time out every batch and fall back to the serial path it exists to replace.
 */
@Component
@Profile("!test")
public class ToolEmbeddingInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolEmbeddingInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final int batchSize;
    /** Counts down when the current backfill finishes; tests wait on it instead of sleeping. */
    private volatile CountDownLatch completed = new CountDownLatch(1);
    /** One backfill at a time: a second trigger while one runs would double-embed the same rows. */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Set on context shutdown so a backfill in flight stops at the next batch boundary. */
    private volatile boolean stopping;

    public ToolEmbeddingInitializer(
            @NonNull JdbcTemplate jdbcTemplate,
            @NonNull EmbeddingModel embeddingModel,
            @Value("${mcp.embedding.backfill-batch-size:8}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.batchSize = Math.max(1, batchSize);
    }

    /** Readiness is already reported when this fires; the backfill must not hold it up. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        backfillAsync();
    }

    /**
     * Starts a backfill on its own thread and returns at once. Also called after each scheduled
     * re-discovery (#1824): rows a later discovery cycle inserts would otherwise stay without an
     * embedding — invisible to selection — until the next restart. A backfill already running is
     * left alone; it will pick up the new rows on its own if they arrive before it queries, and the
     * next cycle catches the rest.
     */
    public void backfillAsync() {
        Thread.ofVirtual().name("tool-embedding-backfill").start(this::backfill);
    }

    /**
     * Stops a backfill in flight at its next batch boundary. Without this a SIGTERM mid-backfill
     * produced one WARN per remaining row once the connection pool had closed.
     */
    @PreDestroy
    public void stop() {
        stopping = true;
    }

    /** Runs the backfill on the calling thread. Package-private so tests can drive it directly. */
    void backfill() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.info("Tool embedding backfill already running; not starting another");
            return;
        }
        completed = new CountDownLatch(1);
        long totalStartNanos = System.nanoTime();
        try {
            // The vector column is the single validated value from RagEmbeddingSettings (V33, #1207),
            // written literally so the SQL stays a constant (java:S2077).
            List<ToolDescriptionRow> rows = jdbcTemplate.query(
                    "SELECT id, name, description FROM mcp_tool WHERE embedding IS NULL",
                    (resultSet, rowNum) -> new ToolDescriptionRow(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("name"),
                            resultSet.getString("description")));
            LOGGER.info("Tool embedding initialization found {} tools missing embeddings", rows.size());
            int populated = 0;
            for (int from = 0; from < rows.size() && !stopping; from += batchSize) {
                List<ToolDescriptionRow> batch = rows.subList(from, Math.min(from + batchSize, rows.size()));
                populated += embedBatch(batch);
                LOGGER.info(
                        "Tool embedding backfill: {} of {} tools populated after {} ms",
                        populated,
                        rows.size(),
                        elapsedMs(totalStartNanos));
            }
            if (stopping) {
                LOGGER.info("Tool embedding backfill stopped by shutdown after {} of {} tools", populated, rows.size());
            }
            LOGGER.info("Populated embeddings for {} tools in {} ms", populated, elapsedMs(totalStartNanos));
        } catch (RuntimeException exception) {
            // Off the main thread now, so nothing else catches this; a backfill that cannot start must
            // be visible in the log rather than vanish with the thread.
            LOGGER.error("Tool embedding backfill aborted after {} ms", elapsedMs(totalStartNanos), exception);
        } finally {
            running.set(false);
            completed.countDown();
        }
    }

    /**
     * Embeds one batch with a single model call and writes each vector back. When the batch call
     * fails, falls back to one call per tool so a single bad description cannot sink the batch.
     */
    private int embedBatch(@NonNull List<ToolDescriptionRow> batch) {
        List<float[]> vectors;
        try {
            vectors = embeddingModel.embed(
                    batch.stream().map(ToolDescriptionRow::description).toList());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Batch embedding of {} tools failed; falling back to one call per tool", batch.size(), exception);
            return embedOneByOne(batch);
        }
        if (vectors.size() != batch.size()) {
            LOGGER.warn(
                    "Batch embedding returned {} vectors for {} tools; falling back to one call per tool",
                    vectors.size(),
                    batch.size());
            return embedOneByOne(batch);
        }
        int populated = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (write(batch.get(i), vectors.get(i))) {
                populated++;
            }
        }
        return populated;
    }

    private int embedOneByOne(@NonNull List<ToolDescriptionRow> batch) {
        int populated = 0;
        for (ToolDescriptionRow row : batch) {
            try {
                if (write(row, embeddingModel.embed(row.description()))) {
                    populated++;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to embed tool {} ({}): {}", row.name(), row.id(), exception.getMessage());
            }
        }
        return populated;
    }

    private boolean write(@NonNull ToolDescriptionRow row, float @NonNull [] vector) {
        try {
            jdbcTemplate.update(
                    "UPDATE mcp_tool SET embedding = ?::vector WHERE id = ?", toVectorPGobject(vector), row.id());
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to store embedding for tool {} ({}): {}", row.name(), row.id(), exception.getMessage());
            return false;
        }
    }

    /** Waits for the current backfill to finish; for tests only, never for readiness. */
    boolean awaitCompletion(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        return completed.await(timeout, unit);
    }

    private static PGobject toVectorPGobject(float[] embedding) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                value.append(",");
            }
            value.append(embedding[i]);
        }
        value.append("]");
        PGobject object = new PGobject();
        object.setType("vector");
        try {
            object.setValue(value.toString());
        } catch (SQLException exception) {
            // value is built from a locally computed embedding array, not client input, and this
            // runs at startup, never from a controller thread (#1694) -- left as a bare
            // IllegalArgumentException.
            throw new IllegalArgumentException("Failed to build vector PGobject", exception);
        }
        return object;
    }

    /** Package-private so the test can build rows without reflection. */
    record ToolDescriptionRow(
            @NonNull UUID id, @NonNull String name, @NonNull String description) {}

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
