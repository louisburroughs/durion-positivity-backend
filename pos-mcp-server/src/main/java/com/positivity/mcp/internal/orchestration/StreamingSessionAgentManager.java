package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
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
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import com.positivity.mcp.service.StreamingSessionAgentCacheMetrics;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.time.Duration;
import java.util.ArrayList;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Component
@Profile("alpha")
public class StreamingSessionAgentManager
        implements StreamingAgentOrchestrationService, StreamingSessionAgentCacheMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSessionAgentManager.class);
    private static final String MEMORY_KEY_SEPARATOR = "::";
    private static final String FULL_TOOL_CACHE_KEY = "full";
    private static final int MAX_LOG_PREVIEW_LENGTH = 160;
    private static final int TIER2_EXPANDED_QUERY_LIMIT = 3;
    private static final int TIER2_RETRIEVAL_CANDIDATES = 20;
    private static final int TIER2_FINAL_TOP_K = 5;

    private final Cache<String, StreamingPosAssistant> roleAgentCache;
    private final Cache<String, ChatMemory> chatMemoryCache;
    private final Cache<String, AtomicInteger> requestCountCache;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore embeddingStore;
    private final MasterAgentRegistry toolRegistry;
    private final ExaWebSearchTool exaWebSearchTool;
    private final InventoryFacadeTool inventoryFacadeTool;
    private final OrderFacadeTool orderFacadeTool;
    private final RolePromptResolver rolePromptResolver;

    @Nullable
    private final ToolRegistryService toolRegistryService;

    @Nullable
    private final ToolAuditService toolAuditService;

    private final int memoryMaxMessages;
    private final int candidateToolLimit;
    private final int rateLimitPerSession;

    public StreamingSessionAgentManager(
            @NonNull StreamingChatModel streamingChatModel,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull PgVectorEmbeddingStore embeddingStore,
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull ExaWebSearchTool exaWebSearchTool,
            @NonNull InventoryFacadeTool inventoryFacadeTool,
            @NonNull OrderFacadeTool orderFacadeTool,
            @NonNull RolePromptResolver rolePromptResolver,
            @Nullable ToolRegistryService toolRegistryService,
            @Nullable ToolAuditService toolAuditService,
            @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
            @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
            @Value("${mcp.agent.memory-max-messages:100}") int memoryMaxMessages,
            @Value("${mcp.agent.candidate-tool-limit:8}") int candidateToolLimit,
            @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.toolRegistry = toolRegistry;
        this.exaWebSearchTool = exaWebSearchTool;
        this.inventoryFacadeTool = inventoryFacadeTool;
        this.orderFacadeTool = orderFacadeTool;
        this.rolePromptResolver = rolePromptResolver;
        this.toolRegistryService = toolRegistryService;
        this.toolAuditService = toolAuditService;
        this.memoryMaxMessages = memoryMaxMessages;
        this.candidateToolLimit = Math.max(1, candidateToolLimit);
        this.rateLimitPerSession = rateLimitPerSession;
        int sanitizedMaxCachedAgents = Math.max(1, maxCachedAgents);
        this.requestCountCache = Caffeine.newBuilder()
                .maximumSize(sanitizedMaxCachedAgents)
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.chatMemoryCache = Caffeine.newBuilder()
                .maximumSize(sanitizedMaxCachedAgents)
                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        this.roleAgentCache = Caffeine.newBuilder()
                .maximumSize(sanitizedMaxCachedAgents)
                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                .build();
        prebuildRoleAgents();
    }

    @Override
    public @NonNull Flux<String> streamChat(@NonNull CurrentUserContext currentUserContext, @NonNull String message) {
        String username = currentUserContext.username();
        String role = currentUserContext.primaryRole();
        AtomicInteger requestCount = requestCountCache.get(username, key -> new AtomicInteger(0));
        if (requestCount.incrementAndGet() > rateLimitPerSession) {
            requestCount.decrementAndGet();
            LOGGER.warn("Rate limit exceeded for username={} userId={}", username, currentUserContext.userId());
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        long startMs = System.currentTimeMillis();
        String memoryId = memoryKey(username, role);
        String messagePreview = preview(message);
        LOGGER.debug(
                "MCP streaming chat dispatch username={} role={} chars={} tokens={} preview=\"{}\"",
                username,
                role,
                message.length(),
                tokenCount(message),
                messagePreview);

        StreamingPosAssistant agent;
        if (toolRegistryService != null) {
            List<Object> roleTools = roleToolsForMessage(role, message);
            List<Object> fallbackTools = fallbackToolsForMessage(message);
            List<Object> allTools = ToolSelectionSupport.mergeWithoutDuplicateToolNames(roleTools,
                    fallbackTools.toArray());
            String cacheKey = toolCacheKey(allTools);
            LOGGER.debug(
                    "MCP streaming tool selection username={} role={} cacheKey={} tools={}",
                    username,
                    role,
                    cacheKey,
                    toolNames(allTools));
            agent = roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + cacheKey, ignored -> buildAgent(role, allTools));
        } else {
            agent = roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, ignored -> buildAgent(role));
        }

        String userContext = formatUserContext(currentUserContext);
        return Flux.<String>create(emitter -> streamTokens(agent, memoryId, message, userContext, emitter))
                .doOnComplete(() -> {
                    int elapsedMs = (int) (System.currentTimeMillis() - startMs);
                    LOGGER.debug(
                            "MCP streaming chat completed username={} role={} totalElapsedMs={} preview=\"{}\"",
                            username,
                            role,
                            elapsedMs,
                            messagePreview);
                    if (toolAuditService != null) {
                        toolAuditService.logToolExecution(null, username, true, false, elapsedMs, null);
                    }
                })
                .doOnError(exception -> {
                    LOGGER.warn(
                            "MCP streaming chat failed username={} role={} preview=\"{}\" error={}",
                            username,
                            role,
                            messagePreview,
                            exception.getClass().getSimpleName());
                    if (toolAuditService != null) {
                        toolAuditService.logToolExecution(
                                null,
                                username,
                                false,
                                false,
                                0,
                                exception.getClass().getSimpleName());
                    }
                });
    }

    @Override
    public void evict(@NonNull String userId) {
        chatMemoryCache.asMap().keySet().removeIf(key -> key.startsWith(userId + MEMORY_KEY_SEPARATOR));
        requestCountCache.invalidate(userId);
    }

    @Override
    public long getCacheSize() {
        return roleAgentCache.estimatedSize();
    }

    private @NonNull StreamingPosAssistant buildAgent(@NonNull String role) {
        List<Object> tools = ToolSelectionSupport.mergeWithoutDuplicateToolNames(
                toolRegistry.resolveToolsForRole(role), exaWebSearchTool);
        return buildAgent(role, tools);
    }

    private @NonNull StreamingPosAssistant buildAgent(@NonNull String role, @NonNull List<Object> tools) {
        long startNanos = System.nanoTime();
        ContentRetriever semanticRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(10)
                .minScore(0.6)
                .build();
        ContentRetriever broadSemanticRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(TIER2_RETRIEVAL_CANDIDATES)
                .minScore(0.55)
                .build();
        ContentRetriever expandedRetriever = new QueryExpansionContentRetriever(
                broadSemanticRetriever, TIER2_EXPANDED_QUERY_LIMIT, TIER2_RETRIEVAL_CANDIDATES);
        ContentRetriever hybridRetriever = new HybridContentRetriever(List.of(semanticRetriever, expandedRetriever),
                TIER2_RETRIEVAL_CANDIDATES);
        ContentRetriever rerankedRetriever = new RerankedContentRetriever(hybridRetriever, TIER2_FINAL_TOP_K);
        ContentRetriever resilientContentRetriever = new ResilientContentRetriever(rerankedRetriever,
                "tier2-hybrid-reranked-retriever");

        StreamingPosAssistant agent = AiServices.builder(StreamingPosAssistant.class)
                .streamingChatModel(streamingChatModel)
                .tools(tools)
                .contentRetriever(resilientContentRetriever)
                .systemMessageProvider(memoryId -> rolePromptResolver.resolvePrompt(role))
                .chatMemoryProvider(this::chatMemoryFor)
                .build();
        LOGGER.debug("Built MCP streaming role agent role={} toolNames={}", role, toolNames(tools));
        LOGGER.info(
                "Built MCP streaming role agent role={} tools={} in {} ms", role, tools.size(), elapsedMs(startNanos));
        return agent;
    }

    private void streamTokens(
            @NonNull StreamingPosAssistant agent,
            @NonNull String memoryId,
            @NonNull String message,
            @NonNull String userContext,
            @NonNull FluxSink<String> emitter) {
        agent.chat(memoryId, message, userContext)
                .onPartialResponse(token -> {
                    if (!emitter.isCancelled()) {
                        emitter.next(token);
                    }
                })
                .onCompleteResponse(response -> emitter.complete())
                .onError(emitter::error)
                .start();
    }

    private void prebuildRoleAgents() {
        long startNanos = System.nanoTime();
        int prebuilt = 0;
        for (String role : toolRegistry.preloadableRoles()) {
            try {
                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
                prebuilt++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to prebuild MCP streaming role agent role={}", role, exception);
            }
        }
        LOGGER.info("Prebuilt {} MCP streaming role agents in {} ms", prebuilt, elapsedMs(startNanos));
    }

    private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
        return chatMemoryCache.get(
                String.valueOf(memoryId), ignored -> MessageWindowChatMemory.withMaxMessages(memoryMaxMessages));
    }

    private static @NonNull String memoryKey(@NonNull String userId, @NonNull String role) {
        return userId + MEMORY_KEY_SEPARATOR + role;
    }

    private @NonNull List<Object> roleToolsForMessage(@NonNull String role, @NonNull String message) {
        List<Object> fullRoleTools = toolRegistry.resolveToolsForRole(role);
        if (toolRegistryService == null) {
            LOGGER.debug(
                    "MCP streaming tool selector unavailable role={} resolvedRoleTools={} queryPreview=\"{}\"",
                    role,
                    toolNames(fullRoleTools),
                    preview(message));
            return fullRoleTools;
        }
        try {
            String workflowState = deriveWorkflowState(message);
            LOGGER.debug(
                    "MCP streaming workflow state derived message preview=\"{}\" workflowState={}",
                    preview(message),
                    workflowState);
            List<ToolMetadata> candidates = toolRegistryService.resolveCandidateTools(
                    new ToolSelectionContext(message, role, workflowState), candidateToolLimit);
            if (LOGGER.isDebugEnabled()) {
                for (int i = 0; i < candidates.size(); i++) {
                    ToolMetadata candidate = candidates.get(i);
                    double confidence = confidenceScore(i, candidate.priority());
                    LOGGER.debug(
                            "MCP streaming tool candidate role={} workflowState={} toolName={} score={} priority={}",
                            role,
                            workflowState,
                            candidate.name(),
                            String.format(Locale.ROOT, "%.3f", confidence),
                            String.format(Locale.ROOT, "%.3f", candidate.priority()));
                }
            }
            List<String> selectedNames = candidates.stream().map(ToolMetadata::name).toList();
            if (selectedNames.isEmpty()) {
                LOGGER.debug(
                        "MCP streaming tool selector returned no candidates role={} queryPreview=\"{}\" fullRoleTools={}; using full role tool set",
                        role,
                        preview(message),
                        toolNames(fullRoleTools));
                return fullRoleTools;
            }
            List<Object> resolvedTools = toolRegistry.resolveToolsForRole(role, selectedNames);
            if (resolvedTools.isEmpty() && !fullRoleTools.isEmpty()) {
                LOGGER.debug(
                        "MCP streaming tool candidates resolved to zero role tools role={} queryPreview=\"{}\" candidateNames={} fullRoleTools={}; using full role tool set",
                        role,
                        preview(message),
                        selectedNames,
                        toolNames(fullRoleTools));
                return fullRoleTools;
            }
            LOGGER.debug(
                    "MCP streaming tool candidates role={} queryPreview=\"{}\" candidateNames={} resolvedRoleTools={}",
                    role,
                    preview(message),
                    selectedNames,
                    toolNames(resolvedTools));
            return resolvedTools;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "MCP streaming tool selection failed role={} queryPreview=\"{}\" error={}; using full role tool set",
                    role,
                    preview(message),
                    exception.getClass().getSimpleName(),
                    exception);
            return fullRoleTools;
        }
    }

    /**
     * Derives workflow state from user message intent.
     */
    private @NonNull String deriveWorkflowState(@NonNull String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, Set.of("save", "create", "add", "new", "submit", "approve", "post"))) {
            return "CREATE";
        } else if (containsAny(lower, Set.of("delete", "remove", "cancel", "void", "purge"))) {
            return "DELETE";
        } else if (containsAny(lower, Set.of("update", "edit", "modify", "change", "put", "patch"))) {
            return "UPDATE";
        } else if (containsAny(lower, Set.of("list", "find", "search", "show", "get", "view", "retrieve", "fetch"))) {
            return "READ";
        } else if (containsAny(lower, Set.of("export", "report", "download", "extract", "backup"))) {
            return "EXPORT";
        }
        return "IDLE";
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
        LOGGER.debug("MCP streaming fallback tool matches tools={}", toolNames(selected));
        return selected;
    }

    private static @NonNull String toolCacheKey(@NonNull List<Object> tools) {
        TreeSet<String> names = new TreeSet<>(Comparator.naturalOrder());
        tools.forEach(tool -> names.add(ClassUtils.getUserClass(tool).getSimpleName()));
        return names.isEmpty() ? "none" : String.join("+", names);
    }

    private static @NonNull List<String> toolNames(@NonNull List<Object> tools) {
        return tools.stream()
                .map(tool -> ClassUtils.getUserClass(tool).getSimpleName())
                .toList();
    }

    private static boolean containsAny(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (text.matches(".*\\b" + token + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }

    private static @NonNull String formatUserContext(@NonNull CurrentUserContext currentUserContext) {
        return "Authenticated user context: username=" + currentUserContext.username()
                + ", userId=" + currentUserContext.userId()
                + ", primaryRole=" + currentUserContext.primaryRole()
                + ", roles=" + currentUserContext.roles()
                + ", authorityCount=" + currentUserContext.authorities().size()
                + ". Interpret references to 'me', 'my', or 'current user' as this authenticated user."
                + " If a question depends on the user's exact permissions, prefer a self-service permissions tool before asking for identifiers.";
    }

    private static int tokenCount(@NonNull String text) {
        return SimpleChatRuleCatalog.tokenize(SimpleChatRuleCatalog.normalize(text))
                .size();
    }

    private static long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static double confidenceScore(int rankIndex, double priority) {
        double rankScore = 1.0 / (rankIndex + 1);
        return Math.clamp((rankScore * 0.7) + (Math.clamp(priority, 0.0, 1.0) * 0.3), 0.0, 1.0);
    }
}
