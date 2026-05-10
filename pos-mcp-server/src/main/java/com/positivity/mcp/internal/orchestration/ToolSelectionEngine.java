package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ToolSelectionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolSelectionEngine.class);

    private final MasterAgentRegistry toolRegistry;
    private final ExaWebSearchTool exaWebSearchTool;
    private final InventoryFacadeTool inventoryFacadeTool;
    private final OrderFacadeTool orderFacadeTool;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;

    @Nullable
    private final ToolRegistryService toolRegistryService;

    private final int candidateToolLimit;

    public ToolSelectionEngine(
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull ExaWebSearchTool exaWebSearchTool,
            @NonNull InventoryFacadeTool inventoryFacadeTool,
            @NonNull OrderFacadeTool orderFacadeTool,
            @Nullable ToolRegistryService toolRegistryService,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
            @Value("${mcp.agent.candidate-tool-limit:8}") int candidateToolLimit) {
        this.toolRegistry = toolRegistry;
        this.exaWebSearchTool = exaWebSearchTool;
        this.inventoryFacadeTool = inventoryFacadeTool;
        this.orderFacadeTool = orderFacadeTool;
        this.toolRegistryService = toolRegistryService;
        this.sharedOrchestrationSupport = sharedOrchestrationSupport;
        this.candidateToolLimit = Math.max(1, candidateToolLimit);
    }

    public @NonNull ToolSelectionResult selectRoleTools(@NonNull String role, @NonNull String message) {
        List<Object> roleTools = roleToolsForMessage(role, message);
        List<Object> fallbackTools = sharedOrchestrationSupport.mergeTools(
            toolRegistry.resolveMasterTools(), fallbackToolsForMessage(message));
        LOGGER.debug(
                "MCP shared tool selection role={} roleTools={} fallbackTools={} queryPreview=\"{}\"",
                role,
                sharedOrchestrationSupport.toolNames(roleTools),
                sharedOrchestrationSupport.toolNames(fallbackTools),
                sharedOrchestrationSupport.preview(message));
        return new ToolSelectionResult(roleTools, fallbackTools);
    }

    public @NonNull List<Object> fullFallbackTools() {
        return sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveMasterTools(), List.of(exaWebSearchTool, inventoryFacadeTool, orderFacadeTool));
    }

    private @NonNull List<Object> roleToolsForMessage(@NonNull String role, @NonNull String message) {
        List<Object> fullRoleTools = toolRegistry.resolveDomainTools(role);
        if (toolRegistryService == null) {
            LOGGER.debug(
                    "MCP shared tool selector unavailable role={} resolvedRoleTools={} queryPreview=\"{}\"",
                    role,
                    sharedOrchestrationSupport.toolNames(fullRoleTools),
                    sharedOrchestrationSupport.preview(message));
            return fullRoleTools;
        }
        try {
            String workflowState = deriveWorkflowState(message);
            LOGGER.debug(
                    "MCP shared workflow state derived message preview=\"{}\" workflowState={}",
                    sharedOrchestrationSupport.preview(message),
                    workflowState);
            List<ToolMetadata> candidates = toolRegistryService.resolveCandidateTools(
                    new ToolSelectionContext(message, role, workflowState), candidateToolLimit);
            if (LOGGER.isDebugEnabled()) {
                for (int i = 0; i < candidates.size(); i++) {
                    ToolMetadata candidate = candidates.get(i);
                    double confidence = confidenceScore(i, candidate.priority());
                    LOGGER.debug(
                            "MCP shared tool candidate role={} workflowState={} toolName={} score={} priority={}",
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
                        "MCP shared tool selector returned no candidates role={} queryPreview=\"{}\" fullRoleTools={}; using full role tool set",
                        role,
                        sharedOrchestrationSupport.preview(message),
                        sharedOrchestrationSupport.toolNames(fullRoleTools));
                return fullRoleTools;
            }
            List<Object> resolvedTools = toolRegistry.resolveDomainTools(role, selectedNames);
            if (resolvedTools.isEmpty() && !fullRoleTools.isEmpty()) {
                LOGGER.debug(
                        "MCP shared tool candidates resolved to zero role tools role={} queryPreview=\"{}\" candidateNames={} fullRoleTools={}; using full role tool set",
                        role,
                        sharedOrchestrationSupport.preview(message),
                        selectedNames,
                        sharedOrchestrationSupport.toolNames(fullRoleTools));
                return fullRoleTools;
            }
            LOGGER.debug(
                    "MCP shared tool candidates role={} queryPreview=\"{}\" candidateNames={} resolvedRoleTools={}",
                    role,
                    sharedOrchestrationSupport.preview(message),
                    selectedNames,
                    sharedOrchestrationSupport.toolNames(resolvedTools));
            return resolvedTools;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "MCP shared tool selection failed role={} queryPreview=\"{}\" error={}; using full role tool set",
                    role,
                    sharedOrchestrationSupport.preview(message),
                    exception.getClass().getSimpleName(),
                    exception);
            return fullRoleTools;
        }
    }

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
        LOGGER.debug(
                "MCP shared fallback tool matches tools={}", sharedOrchestrationSupport.toolNames(selected));
        return selected;
    }

    private static boolean containsAny(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (text.matches(".*\\b" + token + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static double confidenceScore(int rankIndex, double priority) {
        double rankScore = 1.0 / (rankIndex + 1);
        return Math.clamp((rankScore * 0.7) + (Math.clamp(priority, 0.0, 1.0) * 0.3), 0.0, 1.0);
    }

    public record ToolSelectionResult(
            @NonNull List<Object> roleTools,
            @NonNull List<Object> fallbackTools) {}
}
