package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.internal.repository.ScreenRegistryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenLinkResolverImpl — semantic deep-link resolution (rung 3)")
class ScreenLinkResolverImplTest {

    private static final Set<String> VIEW_PERM = Set.of("workorder:workorder:view");
    private final float[] embedding = {0.1f, 0.2f, 0.3f};

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ScreenRegistryRepository repository;

    private ScreenLinkResolverImpl resolver() {
        return new ScreenLinkResolverImpl(embeddingModel, repository, 0.6);
    }

    private ScreenCandidate candidate(String key, String perm, double score) {
        return new ScreenCandidate(
                key, key, "/workorders?status={status}&location={locationId}", "workorder", perm, score);
    }

    @Test
    @DisplayName("top candidate above floor returns a filled, permitted link")
    void topCandidateAboveFloorReturnsFilledUrl() {
        when(embeddingModel.embed("how many workorders are open")).thenReturn(embedding);
        when(repository.findNearest(any(), nullable(String.class), anyInt()))
                .thenReturn(List.of(candidate("workorders.list", "workorder:workorder:view", 0.82)));

        Optional<ScreenLink> link =
                resolver().resolve("how many workorders are open", "workorder", VIEW_PERM, Map.of("status", "OPEN"));

        assertThat(link).isPresent();
        assertThat(link.get().screenKey()).isEqualTo("workorders.list");
        assertThat(link.get().url()).isEqualTo("/workorders?status=OPEN"); // unfilled locationId dropped
        assertThat(link.get().score()).isEqualTo(0.82);
    }

    @Test
    @DisplayName("nothing above the similarity floor returns empty")
    void belowFloorReturnsEmpty() {
        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(repository.findNearest(any(), nullable(String.class), anyInt()))
                .thenReturn(List.of(candidate("workorders.list", null, 0.41)));

        assertThat(resolver().resolve("unrelated question", null, VIEW_PERM, Map.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("permission-gated top candidate is skipped for the next permitted one")
    void permissionGatedTopSkippedNextReturned() {
        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(repository.findNearest(any(), nullable(String.class), anyInt()))
                .thenReturn(List.of(
                        candidate("admin.secret", "admin:secret:view", 0.90),
                        candidate("workorders.list", null, 0.71)));

        Optional<ScreenLink> link = resolver().resolve("show me work", "workorder", VIEW_PERM, Map.of());

        assertThat(link).isPresent();
        assertThat(link.get().screenKey()).isEqualTo("workorders.list");
    }

    @Test
    @DisplayName("blank message returns empty without embedding")
    void blankMessageReturnsEmptyWithoutEmbedding() {
        assertThat(resolver().resolve("   ", null, VIEW_PERM, Map.of())).isEmpty();
        verifyNoInteractions(embeddingModel, repository);
    }
}
