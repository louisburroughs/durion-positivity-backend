package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.service.SystemPromptService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionAgentManager}: cache behaviour and eviction.
 *
 * <p>
 * {@code SessionAgentManager} is annotated {@code @Profile("!test")} so Spring
 * never
 * instantiates it during slice tests. Here we construct it directly via
 * {@code new},
 * bypassing the profile gate, and supply Mockito mocks for all LangChain4j
 * dependencies.
 *
 * <p>
 * {@code AiServices.builder(PosAssistant.class).build()} creates a JDK dynamic
 * proxy.
 * No model calls are made during proxy construction, so a real
 * {@link ExaWebSearchTool}
 * instance (empty API key, never makes HTTP calls) is used rather than a
 * Mockito mock.
 * Using a Mockito mock would trigger a LangChain4j "Duplicated definition for
 * tool: webSearch"
 * error because Mockito subclasses inherit and re-expose the parent's
 * {@code @Tool} annotation.
 */
@ExtendWith(MockitoExtension.class)
class SessionAgentManagerTest {

  @Mock
  private ChatModel chatModel;

  @Mock
  private EmbeddingModel embeddingModel;

  @Mock
  private PgVectorEmbeddingStore embeddingStore;

  @Mock
  private ToolRegistry toolRegistry;

  @Mock
  private SystemPromptService systemPromptService;

  // Real instance required: Mockito subclasses cause @Tool duplicate registration
  // in LangChain4j
  private ExaWebSearchTool exaWebSearchTool;
  private InventoryFacadeTool inventoryFacadeTool;
  private OrderFacadeTool orderFacadeTool;

  private SessionAgentManager manager;

  @BeforeEach
  void setUp() {
    // Return a FRESH list on every invocation so buildAgent mutations don't bleed
    // across calls
    when(toolRegistry.resolveToolsForRole(any())).thenAnswer(inv -> new ArrayList<>());
    exaWebSearchTool = new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
    inventoryFacadeTool = new InventoryFacadeTool(RestClient.builder(), "http://localhost/v1/inventory");
    orderFacadeTool = new OrderFacadeTool(RestClient.builder(), "http://localhost/v1/orders");
    manager = new SessionAgentManager(
        chatModel, embeddingModel, embeddingStore,
        toolRegistry, exaWebSearchTool, inventoryFacadeTool, orderFacadeTool, null, systemPromptService,
        30, 500, 50, 100);
  }

  @Test
  @DisplayName("getOrCreateAgent returns the same proxy instance for the same userId")
  void getOrCreateAgent_returnsSameInstanceForSameUser() {
    PosAssistant first = manager.getOrCreateAgent("user-1", "TECH");
    PosAssistant second = manager.getOrCreateAgent("user-1", "TECH");

    assertThat(first).isSameAs(second);
  }

  @Test
  @DisplayName("getOrCreateAgent returns distinct proxy instances for different userIds")
  void getOrCreateAgent_returnsDifferentInstancesForDifferentUsers() {
    PosAssistant forUser1 = manager.getOrCreateAgent("user-1", "TECH");
    PosAssistant forUser2 = manager.getOrCreateAgent("user-2", "TECH");

    assertThat(forUser1).isNotSameAs(forUser2);
  }

  @Test
  @DisplayName("evict removes the user entry from cache so next call creates a fresh agent")
  void evict_causesNewAgentToBeCreatedOnNextCall() {
    PosAssistant before = manager.getOrCreateAgent("user-1", "TECH");
    manager.evict("user-1");

    // Verify Caffeine cache is empty after invalidation
    @SuppressWarnings("unchecked")
    Cache<String, PosAssistant> cache = (Cache<String, PosAssistant>) ReflectionTestUtils.getField(manager,
        "agentCache");
    assertThat(cache).isNotNull();
    cache.cleanUp();
    assertThat(cache.estimatedSize()).isZero();

    // And the next call builds a brand-new proxy
    PosAssistant after = manager.getOrCreateAgent("user-1", "TECH");
    assertThat(after).isNotSameAs(before);
  }
}
