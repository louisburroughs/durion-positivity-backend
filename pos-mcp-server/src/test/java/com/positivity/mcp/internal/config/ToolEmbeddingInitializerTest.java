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

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    @SuppressWarnings("unchecked")
    private void rowsMissingEmbeddings(int count) {
        List<Object> rows = IntStream.range(0, count)
                .mapToObj(i -> row(UUID.randomUUID(), "tool_" + i, "description " + i))
                .toList();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn((List) rows);
    }

    private static Object row(UUID id, String name, String description) {
        try {
            Class<?> type =
                    Class.forName("com.positivity.mcp.internal.config.ToolEmbeddingInitializer$ToolDescriptionRow");
            var ctor = type.getDeclaredConstructor(UUID.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id, name, description);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("embeds in batches of the configured size and writes every vector back")
    void embedsInBatches() {
        rowsMissingEmbeddings(70);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0))
                        .stream().map(ignored -> new float[] {0.1f, 0.2f}).toList());
        ToolEmbeddingInitializer initializer = new ToolEmbeddingInitializer(jdbcTemplate, embeddingModel, 32);

        initializer.backfill();

        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel, times(3)).embed(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(32, 32, 6);
        verify(embeddingModel, never()).embed(anyString());
        verify(jdbcTemplate, times(70))
                .update(eq("UPDATE mcp_tool SET embedding = ?::vector WHERE id = ?"), any(Object[].class));
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
        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
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
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }
}
