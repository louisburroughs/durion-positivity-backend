package com.positivity.mcp.internal.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolAuditService;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.SessionAgentCacheMetrics;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
@Profile("alpha")
public class SessionAgentManager implements AgentOrchestrationService, SessionAgentCacheMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionAgentManager.class);
    private static final String MEMORY_KEY_SEPARATOR = "::";
    private static final String WORKFLOW_IDLE = "IDLE";
    private static final String FULL_TOOL_CACHE_KEY = "full";
    private static final int MAX_LOG_PREVIEW_LENGTH = 160;

    private final Cache<String, PosAssistant> roleAgentCache;
    private final Cache<String, ChatMemory> chatMemoryCache;
    private final Cache<String, AtomicInteger> requestCountCache;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore embeddingStore;
    private final ToolRegistry toolRegistry;
    private final ExaWebSearchTool exaWebSearchTool;
    private final InventoryFacadeTool inventoryFacadeTool;
    private final OrderFacadeTool orderFacadeTool;

    @Nullable
    private final ToolRegistryService toolRegistryService;

    @Nullable
    private final ToolAuditService toolAuditService;
    private final RolePromptResolver rolePromptResolver;
    private final SimpleChatClassifier simpleChatClassifier;

    private final int memoryMaxMessages;
    private final int candidateToolLimit;
    private final int rateLimitPerSession;

    public SessionAgentManager(
            @NonNull ChatModel chatModel,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull PgVectorEmbeddingStore embeddingStore,
            @NonNull ToolRegistry toolRegistry,
            @NonNull ExaWebSearchTool exaWebSearchTool,
            @NonNull InventoryFacadeTool inventoryFacadeTool,
            @NonNull OrderFacadeTool orderFacadeTool,
            @Nullable ToolRegistryService toolRegistryService,
            @Nullable ToolAuditService toolAuditService,
            @NonNull RolePromptResolver rolePromptResolver,
            @NonNull SimpleChatClassifier simpleChatClassifier,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
            @Value("${mcp.agent.memory-max-messages:50}") int memoryMaxMessages,
            @Value("${mcp.agent.candidate-tool-limit:5}") int candidateToolLimit,
            @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.toolRegistry = toolRegistry;
        this.exaWebSearchTool = exaWebSearchTool;
        this.inventoryFacadeTool = inventoryFacadeTool;
        this.orderFacadeTool = orderFacadeTool;
        this.toolRegistryService = toolRegistryService;
        this.toolAuditService = toolAuditService;
        this.rolePromptResolver = rolePromptResolver;
        this.simpleChatClassifier = simpleChatClassifier;
        this.memoryMaxMessages = memoryMaxMessages;
        this.candidateToolLimit = Math.max(1, candidateToolLimit);
        this.rateLimitPerSession = rateLimitPerSession;
        this.requestCountCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxCachedAgents))
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.chatMemoryCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxCachedAgents))
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.roleAgentCache = Caffeine.newBuilder().maximumSize(Math.max(1, maxCachedAgents)).build();
        prebuildRoleAgents();
    }

    /**
     * Returns a cached role agent, creating one if absent. User-specific
     * conversation state is resolved later by the chat memory provider.
     */
    @NonNull
    PosAssistant getOrCreateAgent(@NonNull String userId, @NonNull String role) {
        return roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, ignored -> buildAgent(role));
    }

    @Override
    public @NonNull String chat(@NonNull String userId, @NonNull String role, @NonNull String message) {
        AtomicInteger requestCount = requestCountCache.get(userId, key -> new AtomicInteger(0));
        if (requestCount.incrementAndGet() > rateLimitPerSession) {
            requestCount.decrementAndGet();
            LOGGER.warn("Rate limit exceeded for userId: {}", userId);
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        long startMs = System.currentTimeMillis();
        String messagePreview = preview(message);
        int tokenCount = tokenCount(message);
        try {
            boolean simpleChat = simpleChatClassifier.isSimpleChat(message);
            LOGGER.debug(
                    "MCP chat request received userId={} role={} simpleChat={} chars={} tokens={} preview=\"{}\"",
                    userId,
                    role,
                    simpleChat,
                    message.length(),
                    tokenCount,
                    messagePreview);
            if (simpleChat) {
                LOGGER.debug("MCP simple chat dispatch userId={} role={} preview=\"{}\"", userId, role, messagePreview);
                return simpleChat(userId, role, message, startMs);
            }

            ToolSelection selection = selectTools(role, message);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool selection for userId={} role={} selectedTools={} preview=\"{}\"",
                        userId,
                        role,
                        selection.toolCount(),
                        messagePreview);
                LOGGER.debug(
                        "MCP agent chat dispatch userId={} role={} cacheKey={} roleTools={} fallbackTools={} preview=\"{}\"",
                        userId,
                        role,
                        selection.cacheKey(),
                        selection.roleToolNames(),
                        selection.fallbackToolNames(),
                        messagePreview);
            }
            PosAssistant agent = getOrCreateAgent(role, selection.cacheKey(), selection.roleTools(),
                    selection.fallbackTools());
            String roleContext = "Current user role: " + role;
            long agentStartNanos = System.nanoTime();
            String response = agent.chat(memoryKey(userId, role), message, roleContext);
            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            LOGGER.info(
                    "MCP agent chat completed role={} selectedTools={} modelElapsedMs={} totalElapsedMs={}",
                    role,
                    selection.toolCount(),
                    elapsedMs(agentStartNanos),
                    elapsedMs);
            if (toolAuditService != null) {
                toolAuditService.logToolExecution(null, userId, true, false, elapsedMs, null);
            }
            return response;
        } catch (RuntimeException exception) {
            int elapsedMs = (int) (System.currentTimeMillis() - startMs);
            LOGGER.warn(
                    "MCP chat failed role={} elapsedMs={} errorName={} ",
                    role,
                    elapsedMs,
                    exception.getClass().getSimpleName(),
                    exception);
            if (toolAuditService != null) {
                toolAuditService.logToolExecution(
                        null,
                        userId,
                        false,
                        false,
                        elapsedMs,
                        exception.getClass().getSimpleName());
            }
            throw exception;
        }
    }

    @Override
    public long getCacheSize() {
        return roleAgentCache.estimatedSize();
    }

    private PosAssistant buildAgent(@NonNull String role) {
        return buildAgent(
                role,
                toolRegistry.resolveToolsForRole(role),
                List.of(exaWebSearchTool, inventoryFacadeTool, orderFacadeTool));
    }

    private PosAssistant buildAgent(
            @NonNull String role, @NonNull Collection<Object> roleTools, @NonNull Collection<Object> fallbackTools) {
        long startNanos = System.nanoTime();
        // 1. Resolve role-specific tools and append fallback tools without
        // registering duplicate @Tool method names.
        List<Object> tools = ToolSelectionSupport.mergeWithoutDuplicateToolNames(roleTools,
                fallbackTools.toArray(Object[]::new));

        // 2. Build RAG content retriever
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.7)
                .build();
        ContentRetriever resilientContentRetriever = new ResilientContentRetriever(contentRetriever,
                "embedding-store-content-retriever");

        // 3. Assemble AiServices proxy. Chat memory remains per user+role through
        // the provider, so role-level agent prebuilds do not share conversations.
        PosAssistant agent = AiServices.builder(PosAssistant.class)
                .chatModel(chatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .systemMessageProvider(memoryId -> rolePromptResolver.resolvePrompt(role))
                .chatMemoryProvider(this::chatMemoryFor)
                .build();
        LOGGER.debug("Built MCP role agent role={} toolNames={}", role, toolNames(tools));
        LOGGER.info("Built MCP role agent role={} tools={} in {} ms", role, tools.size(), elapsedMs(startNanos));
        return agent;
    }

    private @NonNull PosAssistant getOrCreateAgent(
            @NonNull String role,
            @NonNull String toolCacheKey,
            @NonNull List<Object> roleTools,
            @NonNull List<Object> fallbackTools) {
        return roleAgentCache.get(
                role + MEMORY_KEY_SEPARATOR + toolCacheKey, ignored -> buildAgent(role, roleTools, fallbackTools));
    }

    /**
     * Evicts a user's conversation state and rate counter. Role agents remain
     * cached.
     */
    @Override
    public void evict(@NonNull String userId) {
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        for (String role : toolRegistry.preloadableRoles()) {
            try {
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP role agents in {} ms", prebuilt, elapsedMs(startNanos));
    }

    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        return chatMemoryCache.get(
                String.valueOf(memoryId), ignored -> MessageWindowChatMemory.withMaxMessages(memoryMaxMessages));
    }

    private @NonNull String simpleChat(
            @NonNull String userId, @NonNull String role, @NonNull String message, long requestStartMs) {
        long simpleStartNanos = System.nanoTime();
        String prompt = rolePromptResolver.resolvePrompt(role);
        String response = chatModel
                .chat(List.of(SystemMessage.from(prompt), UserMessage.from(message)))
                .aiMessage()
                .text();
        int elapsedMs = (int) (System.currentTimeMillis() - requestStartMs);
        LOGGER.info(
                "MCP simple chat completed role={} modelElapsedMs={} totalElapsedMs={}",
                role,
                elapsedMs(simpleStartNanos),
                elapsedMs);
        if (toolAuditService != null) {
            toolAuditService.logToolExecution(null, userId, true, false, elapsedMs, null);
        }
        return response;
    }

    private @NonNull ToolSelection selectTools(@NonNull String role, @NonNull String message) {
        List<Object> roleTools = roleToolsForMessage(role, message);
        List<Object> fallbackTools = fallbackToolsForMessage(message);
        return new ToolSelection(roleTools, fallbackTools);
    }

    private @NonNull List<Object> roleToolsForMessage(@NonNull String role, @NonNull String message) {
        List<Object> fullRoleTools = toolRegistry.resolveToolsForRole(role);
        if (toolRegistryService == null) {
            LOGGER.debug("MCP tool selector unavailable role={} resolvedRoleTools={}", role, toolNames(fullRoleTools));
            return fullRoleTools;
        }
        try {
            List<String> selectedNames = toolRegistryService
                    .resolveCandidateTools(new ToolSelectionContext(message, role, WORKFLOW_IDLE), candidateToolLimit)
                    .stream()
                    .map(ToolMetadata::name)
                    .toList();
            if (selectedNames.isEmpty()) {
                LOGGER.debug(
                        "MCP tool selector returned no candidates role={} fullRoleTools={}; using full role tool set",
                        role,
                        toolNames(fullRoleTools));
                return fullRoleTools;
            }
            List<Object> resolvedTools = toolRegistry.resolveToolsForRole(role, selectedNames);
            if (resolvedTools.isEmpty() && !fullRoleTools.isEmpty()) {
                LOGGER.debug(
                        "MCP tool candidates resolved to zero role tools role={} candidateNames={} fullRoleTools={}; using full role tool set",
                        role,
                        selectedNames,
                        toolNames(fullRoleTools));
                return fullRoleTools;
            }
            LOGGER.debug(
                    "MCP tool candidates role={} candidateNames={} resolvedRoleTools={}",
                    role,
                    selectedNames,
                    toolNames(resolvedTools));
            return resolvedTools;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "MCP tool selection failed role={} error={}; using full role tool set",
                    role,
                    exception.getClass().getSimpleName(),
                    exception);
            return fullRoleTools;
        }
    }

    private @NonNull List<Object> fallbackToolsForMessage(@NonNull String message) {
        String text = message.toLowerCase(Locale.ROOT);
        List<Object> selected = new ArrayList<>();
        if (containsAny(text, Set.of("current", "internet", "news", "online", "recent", "web"))) {
            selected.add(exaWebSearchTool);
        }
        if (containsAny(
                text, Set.of("availability", "inventory", "location", "part", "product", "sku", "stock", "store"))) {
            selected.add(inventoryFacadeTool);
        }
        if (containsAny(text, Set.of("order", "po", "purchase", "sale", "sales"))) {
            selected.add(orderFacadeTool);
        }
        LOGGER.debug("MCP fallback tool matches tools={}", toolNames(selected));
        return selected;
    }

    private static @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }

    private static int tokenCount(@NonNull String text) {
        return SimpleChatRuleCatalog.tokenize(SimpleChatRuleCatalog.normalize(text)).size();
    }

    private static @NonNull List<String> toolNames(@NonNull Collection<Object> tools) {
        return tools.stream().map(SessionAgentManager::toolName).toList();
    }

    private static @NonNull String toolName(@NonNull Object tool) {
        return ClassUtils.getUserClass(tool).getSimpleName();
    }

    private static boolean containsAny(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (text.matches(".*\\b" + token + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull String memoryKey(@NonNull String userId, @NonNull String role) {
        return userId + MEMORY_KEY_SEPARATOR + role;
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record ToolSelection(
            @NonNull List<Object> roleTools, @NonNull List<Object> fallbackTools) {

        int toolCount() {
            return roleTools.size() + fallbackTools.size();
        }

        @NonNull
        String cacheKey() {
            TreeSet<String> names = new TreeSet<>(Comparator.naturalOrder());
            roleTools.forEach(tool -> names.add(toolName(tool)));
            fallbackTools.forEach(tool -> names.add(toolName(tool)));
            return names.isEmpty() ? "none" : String.join("+", names);
        }

        @NonNull
        List<String> roleToolNames() {
            return toolNames(roleTools);
        }

        @NonNull
        List<String> fallbackToolNames() {
            return toolNames(fallbackTools);
        }
    }
}
