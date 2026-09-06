package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * #1818: the backfill must not decide readiness, and a large backlog must cost one model call per
 * batch rather than per tool. On alpha 884 tools at one call each took twenty minutes before the
 * application reported ready, and the deploy's health wait gave up.
 */
@DisplayName("ToolEmbeddingInitializer — off the readiness path, in batches")
class ToolEmbeddingInitializerTest {

    private static final String UPDATE_SQL = "UPDATE mcp_tool SET embedding = ?::vector WHERE id = ?";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private static UUID idOf(int i) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", i));
    }

    @SuppressWarnings("unchecked")
    private void rowsMissingEmbeddings(int count) {
        List<Object> rows = IntStream.range(0, count)
                .mapToObj(i -> (Object)
                        new ToolEmbeddingInitializer.ToolDescriptionRow(idOf(i), "tool_" + i, "description " + i))
                .toList();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn((List) rows);
    }

    /** Vector i encodes the description's index so id/vector pairing can be asserted on the write. */
    @SuppressWarnings("unchecked")
    private void batchesAnswerIndexVectors() {
        when(embeddingModel.embed(anyList()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0))
                        .stream()
                                .map(text ->
                                        new float[] {Float.parseFloat(text.substring("description ".length())), 0.5f})
                                .toList());
    }

    @Test
    @DisplayName("embeds in batches of the configured size, in row order, and writes each row its own vector")
    void embedsInBatches() {
        rowsMissingEmbeddings(70);
        batchesAnswerIndexVectors();
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill();

        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel, times(3)).embed(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(32, 32, 6);
        assertThat(batches.getAllValues().get(0).get(0)).isEqualTo("description 0");
        assertThat(batches.getAllValues().get(2).get(5)).isEqualTo("description 69");
        verify(embeddingModel, never()).embed(anyString());
        // Mockito 5: any(Object[].class) captures the whole varargs array of the update overload.
        ArgumentCaptor<Object[]> writes = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(70)).update(eq(UPDATE_SQL), writes.capture());
        assertThat(writes.getAllValues().get(0)[1]).isEqualTo(idOf(0));
        assertThat(writes.getAllValues().get(69)[1]).isEqualTo(idOf(69));
        assertThat(writes.getAllValues().get(69)[0].toString()).isEqualTo("[69.0,0.5]");
    }

    @Test
    @DisplayName("a failing batch falls back to one call per tool, so one bad description cannot sink the rest")
    void batchFailureFallsBackPerTool() {
        rowsMissingEmbeddings(3);
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalStateException("upstream 500"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {1f});
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill();

        verify(embeddingModel, times(3)).embed(anyString());
        verify(jdbcTemplate, times(3)).update(eq(UPDATE_SQL), any(Object[].class));
    }

    @Test
    @DisplayName("a batch that returns the wrong number of vectors falls back to one call per tool")
    void countMismatchFallsBackPerTool() {
        rowsMissingEmbeddings(4);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[] {1f}));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {2f});
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill();

        verify(embeddingModel, times(4)).embed(anyString());
        verify(jdbcTemplate, times(4)).update(eq(UPDATE_SQL), any(Object[].class));
    }

    @Test
    @DisplayName("a write that fails is not fatal — the remaining rows are still written")
    void writeFailureDoesNotAbort() {
        rowsMissingEmbeddings(3);
        batchesAnswerIndexVectors();
        when(jdbcTemplate.update(eq(UPDATE_SQL), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(1)
                .thenReturn(1);
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill();

        verify(jdbcTemplate, times(3)).update(eq(UPDATE_SQL), any(Object[].class));
    }

    @Test
    @DisplayName("nothing missing means no model call at all")
    void nothingMissingMeansNoModelCall() {
        rowsMissingEmbeddings(0);
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill();

        verify(embeddingModel, never()).embed(anyList());
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    @DisplayName("shutdown stops the backfill at the next batch boundary")
    void stopEndsTheBackfillAtTheNextBatch() {
        rowsMissingEmbeddings(6);
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 2);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            initializer.stop();
            return ((List<?>) invocation.getArgument(0))
                    .stream().map(ignored -> new float[] {1f}).toList();
        });

        initializer.backfill();

        // The batch in flight completes and is written; no second batch starts.
        verify(embeddingModel, times(1)).embed(anyList());
        verify(jdbcTemplate, times(2)).update(eq(UPDATE_SQL), any(Object[].class));
    }

    @Test
    @DisplayName("the ready event returns before the backfill finishes — readiness never waits for it")
    void readyEventDoesNotBlockOnTheBackfill() throws Exception {
        rowsMissingEmbeddings(1);
        CountDownLatch release = new CountDownLatch(1);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of(new float[] {1f});
        });
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        long before = System.nanoTime();
        initializer.onApplicationReady();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        // The listener returned while the model call is still parked on the latch.
        assertThat(elapsedMs).isLessThan(1_000);
        assertThat(initializer.awaitCompletion(50, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(initializer.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        verify(jdbcTemplate).update(eq(UPDATE_SQL), any(Object[].class));
    }

    @Test
    @DisplayName("two consecutive runs each get their own latch — a waiter on the second never sees the first's")
    void consecutiveRunsHaveIndependentLatches() throws Exception {
        rowsMissingEmbeddings(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                releaseSecond.await(5, TimeUnit.SECONDS);
            }
            return List.of(new float[] {1f});
        });
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill(); // first run, synchronous, completes
        initializer.backfillAsync(); // second run, parked on the latch

        // The second run's latch is in place before backfillAsync returned, so this waits on it —
        // not on the first run's already-released one.
        assertThat(initializer.awaitCompletion(100, TimeUnit.MILLISECONDS)).isFalse();
        releaseSecond.countDown();
        assertThat(initializer.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        verify(jdbcTemplate, times(2)).update(eq(UPDATE_SQL), any(Object[].class));
    }

    @Test
    @DisplayName("a trigger while a run is in flight is refused, not queued")
    void secondTriggerWhileRunningIsRefused() throws Exception {
        rowsMissingEmbeddings(1);
        CountDownLatch release = new CountDownLatch(1);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of(new float[] {1f});
        });
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfillAsync();
        initializer.backfillAsync();
        release.countDown();

        assertThat(initializer.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
        verify(embeddingModel, times(1)).embed(anyList());
    }
}
