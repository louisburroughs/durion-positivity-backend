package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link StreamingSessionAgentManager}: cache behaviour and
 * eviction.
 *
 * <p>
 * The manager is {@code @Profile("alpha")} so it is constructed directly via
 * {@code new}, bypassing the Spring profile gate.
 *
 * <p>
 * A real {@link ExaWebSearchTool} instance (empty API key, never makes HTTP
 * calls) is used rather than a Mockito mock to avoid LangChain4j's
 * "Duplicated definition for tool: webSearch" error caused by Mockito
 * subclasses
 * re-exposing the parent {@code @Tool} annotation.
 *
 * <p>
 * {@code AiServices.builder(StreamingPosAssistant.class).build()} creates a JDK
 * dynamic proxy at agent-build time without invoking the model, so all three
 * scenarios can verify cache hit/miss behaviour without triggering real LLM
 * calls.
 */
@ExtendWith(MockitoExtension.class)
class StreamingSessionAgentManagerTest {

    @Mock
    private StreamingChatModel streamingChatModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private PgVectorEmbeddingStore embeddingStore;

    @Mock
    private ToolRegistry toolRegistry;

    // Real instance required to prevent @Tool duplicate registration
    private ExaWebSearchTool exaWebSearchTool;

    private StreamingSessionAgentManager manager;

    @BeforeEach
    void setUp() {
        // Return a fresh mutable list each invocation so buildAgent mutations don't
        // bleed
        when(toolRegistry.resolveToolsForRole(any())).thenAnswer(inv -> new ArrayList<>());
        exaWebSearchTool = new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
        manager = new StreamingSessionAgentManager(
                streamingChatModel,
                embeddingModel,
                embeddingStore,
                toolRegistry,
                exaWebSearchTool,
                null,
                30,
                500,
                50,
                100);
    }

    @Test
    @DisplayName("streamChat returns a non-null Flux")
    void streamChat_returnsNonNullFlux() {
        Flux<String> result = manager.streamChat("user-1", "ROLE_CASHIER", "hello");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("streamChat reuses cached agent for same userId+role")
    void streamChat_cachesAgentForSameUser() {
        manager.streamChat("user-1", "ROLE_CASHIER", "first message");
        manager.streamChat("user-1", "ROLE_CASHIER", "second message");

        // buildAgent calls toolRegistry.resolveToolsForRole; cache hit means only one
        // call
        verify(toolRegistry, times(1)).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("evict removes cached agent so next streamChat rebuilds it")
    void evict_removesFromCache() {
        manager.streamChat("user-1", "ROLE_CASHIER", "first message");
        manager.evict("user-1");
        manager.streamChat("user-1", "ROLE_CASHIER", "after evict");

        // Agent was rebuilt after eviction: resolveToolsForRole called twice
        verify(toolRegistry, times(2)).resolveToolsForRole("ROLE_CASHIER");
    }

    @Test
    @DisplayName("streamChat rebuilds agent when role changes for same userId")
    void streamChat_roleChange_rebuildsAgent() {
        manager.streamChat("user-1", "ROLE_CASHIER", "first");
        manager.streamChat("user-1", "ROLE_MANAGER", "second");

        verify(toolRegistry, times(1)).resolveToolsForRole("ROLE_CASHIER");
        verify(toolRegistry, times(1)).resolveToolsForRole("ROLE_MANAGER");
    }

    @Test
    @DisplayName("streamChat skips fallback tools already resolved for the role")
    void streamChat_skipsDuplicateFallbackTool() {
        when(toolRegistry.resolveToolsForRole("ROLE_MANAGER"))
                .thenAnswer(inv -> new ArrayList<>(List.of(exaWebSearchTool)));

        Flux<String> result = manager.streamChat("user-with-role-tool", "ROLE_MANAGER", "hello");

        assertThat(result).isNotNull();
    }
}
