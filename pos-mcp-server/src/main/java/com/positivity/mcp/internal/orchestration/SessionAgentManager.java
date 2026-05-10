package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.orchestration.memory.SemanticChatMemoryStore;
import com.positivity.mcp.internal.orchestration.memory.SessionSummaryService;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.RolePromptResolver;
import com.positivity.mcp.service.SessionAgentCacheMetrics;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpha")
public class SessionAgentManager implements AgentOrchestrationService, SessionAgentCacheMetrics {

        private static final Logger LOGGER = LoggerFactory.getLogger(SessionAgentManager.class);
        private static final String MEMORY_KEY_SEPARATOR = "::";
        private static final String FULL_TOOL_CACHE_KEY = "full";
        private static final int TIER2_EXPANDED_QUERY_LIMIT = 3;
        private static final int TIER2_RETRIEVAL_CANDIDATES = 20;
        private static final int TIER2_FINAL_TOP_K = 5;

        private final Cache<String, PosAssistant> roleAgentCache;
        private final Cache<String, ChatMemory> chatMemoryCache;
        private final Cache<String, AtomicInteger> requestCountCache;
        private final ChatModel chatModel;
        private final EmbeddingModel embeddingModel;
        private final PgVectorEmbeddingStore embeddingStore;
        private final MasterAgentRegistry toolRegistry;
        private final SharedOrchestrationSupport sharedOrchestrationSupport;
        private final ToolSelectionEngine toolSelectionEngine;
        private final ScopedContentRetrieverFactory scopedContentRetrieverFactory;

        @Nullable
        private final ToolExecutionAuditLogger toolExecutionAuditLogger;

        @Nullable
        private final SessionSummaryService sessionSummaryService;

        private final RolePromptResolver rolePromptResolver;
        private final SimpleChatClassifier simpleChatClassifier;

        private final int memoryMaxMessages;
        private final int rateLimitPerSession;

        public SessionAgentManager(
                        @NonNull ChatModel chatModel,
                        @NonNull EmbeddingModel embeddingModel,
                        @NonNull PgVectorEmbeddingStore embeddingStore,
                        @NonNull MasterAgentRegistry toolRegistry,
                        @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
                        @NonNull ToolSelectionEngine toolSelectionEngine,
                        @NonNull ScopedContentRetrieverFactory scopedContentRetrieverFactory,
                        @Nullable ToolExecutionAuditLogger toolExecutionAuditLogger,
                        @Nullable SessionSummaryService sessionSummaryService,
                        @NonNull RolePromptResolver rolePromptResolver,
                        @NonNull SimpleChatClassifier simpleChatClassifier,
                        @Value("${mcp.agent.cache-ttl-minutes:30}") int cacheTtlMinutes,
                        @Value("${mcp.agent.max-cached-agents:500}") int maxCachedAgents,
                        @Value("${mcp.agent.memory-max-messages:100}") int memoryMaxMessages,
                        @Value("${pos.nlti.rate-limit.per-session:100}") int rateLimitPerSession) {
                this.chatModel = chatModel;
                this.embeddingModel = embeddingModel;
                this.embeddingStore = embeddingStore;
                this.toolRegistry = toolRegistry;
                this.sharedOrchestrationSupport = sharedOrchestrationSupport;
                this.toolSelectionEngine = toolSelectionEngine;
                this.scopedContentRetrieverFactory = scopedContentRetrieverFactory;
                this.toolExecutionAuditLogger = toolExecutionAuditLogger;
                this.sessionSummaryService = sessionSummaryService;
                this.rolePromptResolver = rolePromptResolver;
                this.simpleChatClassifier = simpleChatClassifier;
                this.memoryMaxMessages = memoryMaxMessages;
                this.rateLimitPerSession = rateLimitPerSession;
                this.requestCountCache = Caffeine.newBuilder()
                                .maximumSize(Math.max(1, maxCachedAgents))
                                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                                .build();
                this.chatMemoryCache = Caffeine.newBuilder()
                                .maximumSize(Math.max(1, maxCachedAgents))
                                .expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))
                                .build();
                this.roleAgentCache = Caffeine.newBuilder()
                                .maximumSize(Math.max(1, maxCachedAgents))
                                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                                .build();
                prebuildRoleAgents();
        }

        /**
         * Returns a cached role agent, creating one if absent. User-specific
         * conversation state is resolved later by the chat memory provider.
         */
        @NonNull
        PosAssistant getOrCreateAgent(@NonNull String userId, @NonNull String role) {
                return roleAgentCache.get(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY,
                                ignored -> buildAgent(role));
        }

        @Override
        public @NonNull String chat(@NonNull CurrentUserContext currentUserContext, @NonNull String message) {
                String username = currentUserContext.username();
                String role = currentUserContext.primaryRole();
                AtomicInteger requestCount = requestCountCache.get(username, key -> new AtomicInteger(0));
                if (requestCount.incrementAndGet() > rateLimitPerSession) {
                        requestCount.decrementAndGet();
                        LOGGER.warn("Rate limit exceeded for username={} userId={}", username,
                                        currentUserContext.userId());
                        throw new RateLimitExceededException("Rate limit exceeded");
                }

                long startMs = System.currentTimeMillis();
                String messagePreview = sharedOrchestrationSupport.preview(message);
                int tokenCount = tokenCount(message);
                try {
                        boolean simpleChat = simpleChatClassifier.isSimpleChat(message);
                        LOGGER.debug(
                                        "MCP chat request received username={} role={} simpleChat={} chars={} tokens={} preview=\"{}\"",
                                        username,
                                        role,
                                        simpleChat,
                                        message.length(),
                                        tokenCount,
                                        messagePreview);
                        if (simpleChat) {
                                LOGGER.debug(
                                                "MCP simple chat dispatch username={} role={} preview=\"{}\"", username,
                                                role, messagePreview);
                                return simpleChat(currentUserContext, message, startMs);
                        }

                        ToolSelectionEngine.ToolSelectionResult selection = toolSelectionEngine.selectRoleTools(role,
                                        message);
                        List<Object> selectedTools = sharedOrchestrationSupport.mergeTools(selection.roleTools(),
                                        selection.fallbackTools());
                        String cacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
                        if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                                "MCP tool selection for username={} role={} selectedTools={} preview=\"{}\"",
                                                username,
                                                role,
                                                selectedTools.size(),
                                                messagePreview);
                                LOGGER.debug(
                                                "MCP agent chat dispatch username={} role={} cacheKey={} roleTools={} fallbackTools={} preview=\"{}\"",
                                                username,
                                                role,
                                                cacheKey,
                                                sharedOrchestrationSupport.toolNames(selection.roleTools()),
                                                sharedOrchestrationSupport.toolNames(selection.fallbackTools()),
                                                messagePreview);
                        }
                        PosAssistant agent = getOrCreateAgent(role, cacheKey, selection.roleTools(),
                                        selection.fallbackTools());
                        long agentStartNanos = System.nanoTime();
                        String response = agent.chat(memoryKey(username, role), message,
                                        formatUserContext(currentUserContext));
                        int elapsedMs = (int) (System.currentTimeMillis() - startMs);
                        LOGGER.info(
                                        "MCP agent chat completed role={} selectedTools={} modelElapsedMs={} totalElapsedMs={}",
                                        role,
                                        selectedTools.size(),
                                        elapsedMs(agentStartNanos),
                                        elapsedMs);
                        if (toolExecutionAuditLogger != null) {
                                toolExecutionAuditLogger.logToolExecution(null, username, true, false, elapsedMs, null);
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
                        if (toolExecutionAuditLogger != null) {
                                toolExecutionAuditLogger.logToolExecution(
                                                null,
                                                username,
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
                return buildAgent(role, toolRegistry.resolveDomainTools(role), toolSelectionEngine.fullFallbackTools());
        }

        private PosAssistant buildAgent(
                        @NonNull String role, @NonNull Collection<Object> roleTools,
                        @NonNull Collection<Object> fallbackTools) {
                long startNanos = System.nanoTime();
                List<Object> tools = sharedOrchestrationSupport.mergeTools(roleTools, fallbackTools);
                String ragScope = toolRegistry.resolveRagScopeForTools(tools);

                // 2. Tier 2 retrieval pipeline: semantic + expanded + hybrid + re-ranking.
                ContentRetriever semanticRetriever = scopedContentRetrieverFactory.create(ragScope, 10, 0.6);
                ContentRetriever broadSemanticRetriever = scopedContentRetrieverFactory.create(ragScope,
                                TIER2_RETRIEVAL_CANDIDATES, 0.55);
                ContentRetriever expandedRetriever = new QueryExpansionContentRetriever(
                                broadSemanticRetriever, TIER2_EXPANDED_QUERY_LIMIT, TIER2_RETRIEVAL_CANDIDATES);
                ContentRetriever hybridRetriever = new HybridContentRetriever(
                                List.of(semanticRetriever, expandedRetriever), TIER2_RETRIEVAL_CANDIDATES);
                ContentRetriever rerankedRetriever = new RerankedContentRetriever(hybridRetriever, TIER2_FINAL_TOP_K);
                ContentRetriever resilientContentRetriever = new ResilientContentRetriever(rerankedRetriever,
                                "tier2-hybrid-reranked-retriever");

                // Tier 3: Role-aware metadata filtering (deferred to dynamic context resolution
                // at runtime)
                // Note: RoleAwareMetadataFilter requires user roles from SecurityContext.
                // Currently applied at chat boundary where user context is available.

                // 3. Assemble AiServices proxy. Chat memory remains per user+role through
                // the provider, so role-level agent prebuilds do not share conversations.
                PosAssistant agent = AiServices.builder(PosAssistant.class)
                                .chatModel(chatModel)
                                .tools(tools)
                                .contentRetriever(resilientContentRetriever)
                                .systemMessageProvider(memoryId -> rolePromptResolver.resolvePrompt(role))
                                .chatMemoryProvider(this::chatMemoryFor)
                                .build();
                LOGGER.debug(
                                "Built MCP role agent role={} ragScope={} toolNames={}",
                                role,
                                ragScope,
                                sharedOrchestrationSupport.toolNames(tools));
                LOGGER.info(
                                "Built MCP role agent role={} ragScope={} tools={} in {} ms",
                                role,
                                ragScope,
                                tools.size(),
                                elapsedMs(startNanos));
                return agent;
        }

        private @NonNull PosAssistant getOrCreateAgent(
                        @NonNull String role,
                        @NonNull String toolCacheKey,
                        @NonNull List<Object> roleTools,
                        @NonNull List<Object> fallbackTools) {
                return roleAgentCache.get(
                                role + MEMORY_KEY_SEPARATOR + toolCacheKey,
                                ignored -> buildAgent(role, roleTools, fallbackTools));
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
                for (String role : toolRegistry.preloadableRoleIdentifiers()) {
                        try {
                                ToolSelectionEngine.ToolSelectionResult selection = toolSelectionEngine
                                                .selectRoleTools(role, role);
                                List<Object> selectedTools = sharedOrchestrationSupport
                                                .mergeTools(selection.roleTools(), selection.fallbackTools());
                                String warmCacheKey = sharedOrchestrationSupport.toolCacheKey(selectedTools);
                                roleAgentCache.put(
                                                role + MEMORY_KEY_SEPARATOR + warmCacheKey,
                                                buildAgent(role, selection.roleTools(), selection.fallbackTools()));

                                // Keep the legacy full key warm for direct role-level access paths.
                                roleAgentCache.put(role + MEMORY_KEY_SEPARATOR + FULL_TOOL_CACHE_KEY, buildAgent(role));
                                prebuilt++;
                        } catch (RuntimeException exception) {
                                LOGGER.warn("Failed to prebuild MCP role agent role={}", role, exception);
                        }
                }
                LOGGER.info("Prebuilt {} MCP role agents in {} ms", prebuilt, elapsedMs(startNanos));
        }

        private @NonNull ChatMemory chatMemoryFor(@NonNull Object memoryId) {
                // Tier 3: Replace MessageWindowChatMemory with SemanticChatMemoryStore
                // for persistent semantic memory and session summarization
                return chatMemoryCache.get(
                                String.valueOf(memoryId),
                                ignored -> new SemanticChatMemoryStore(
                                                memoryMaxMessages, chatModel, embeddingModel, embeddingStore,
                                                sessionSummaryService));
        }

        private @NonNull String simpleChat(
                        @NonNull CurrentUserContext currentUserContext, @NonNull String message, long requestStartMs) {
                long simpleStartNanos = System.nanoTime();
                String prompt = rolePromptResolver.resolvePrompt(currentUserContext.primaryRole())
                                + System.lineSeparator()
                                + formatUserContext(currentUserContext);
                String response = chatModel
                                .chat(List.of(SystemMessage.from(prompt), UserMessage.from(message)))
                                .aiMessage()
                                .text();
                int elapsedMs = (int) (System.currentTimeMillis() - requestStartMs);
                LOGGER.info(
                                "MCP simple chat completed role={} modelElapsedMs={} totalElapsedMs={}",
                                currentUserContext.primaryRole(),
                                elapsedMs(simpleStartNanos),
                                elapsedMs);
                if (toolExecutionAuditLogger != null) {
                        toolExecutionAuditLogger.logToolExecution(null, currentUserContext.username(), true, false,
                                        elapsedMs, null);
                }
                return response;
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

        private static @NonNull String memoryKey(@NonNull String userId, @NonNull String role) {
                return userId + MEMORY_KEY_SEPARATOR + role;
        }

        private static long elapsedMs(long startNanos) {
                return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }

}
