package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.domain.WorkflowState;
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

    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role, @NonNull Set<String> permissionCodes, @NonNull String message) {
        // Session-less callers (e.g. /v1/mcp/chat) fall back to message-heuristic derivation.
        return selectRoleTools(role, permissionCodes, message, deriveWorkflowState(message));
    }

    /**
     * Gate 2C: workflow-state-aware selection. {@code workflowState} is an explicit input so a
     * session-bearing caller can supply the persisted {@code NltiSession} state rather than relying
     * on message-text heuristics.
     */
    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState) {
        List<Object> roleTools = roleToolsForMessage(role, permissionCodes, message, workflowState);
        List<Object> fallbackTools = sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveMasterTools(), fallbackToolsForMessage(message));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool selection role={} permissionCodes={} workflowState={} roleTools={} fallbackTools={} queryPreview=\"{}\"",
                    role,
                    permissionCodes,
                    workflowState,
                    sharedOrchestrationSupport.toolNames(roleTools),
                    sharedOrchestrationSupport.toolNames(fallbackTools),
                    sharedOrchestrationSupport.preview(message));
        }
        return new ToolSelectionResult(roleTools, fallbackTools);
    }

    public @NonNull List<Object> fullFallbackTools() {
        return sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveMasterTools(), List.of(exaWebSearchTool, inventoryFacadeTool, orderFacadeTool));
    }

    private @NonNull List<Object> roleToolsForMessage(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState) {
        List<Object> fullRoleTools = toolRegistry.resolveDomainTools(role);
        if (toolRegistryService == null) {
            logToolSelectorUnavailable(role, permissionCodes, message, fullRoleTools);
            return fullRoleTools;
        }
        try {
            logWorkflowState(message, workflowState.name());
            List<ToolMetadata> candidates = toolRegistryService.resolveCandidateTools(
                    new ToolSelectionContext(message, role, workflowState.name(), permissionCodes), candidateToolLimit);
            logCandidates(role, permissionCodes, workflowState.name(), candidates);
            List<String> selectedNames =
                    candidates.stream().map(ToolMetadata::name).toList();
            if (selectedNames.isEmpty()) {
                return logNoCandidates(role, permissionCodes, message, fullRoleTools);
            }
            // Resolve names across the full registered tool set (not role-scoped): permission gating
            // + scoring already ran in ToolRegistryService, and tools are bucketed by domain, so a
            // role-scoped lookup (resolveDomainTools(role, names)) can never match. See Gate 2B / #780.
            List<Object> resolvedTools = toolRegistry.resolveToolsByName(selectedNames);
            if (resolvedTools.isEmpty() && !fullRoleTools.isEmpty()) {
                return logResolvedToZeroTools(role, permissionCodes, message, selectedNames, fullRoleTools);
            }
            logResolvedCandidates(role, permissionCodes, message, selectedNames, resolvedTools);
            return resolvedTools;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "MCP shared tool selection failed role={} permissionCodes={} queryPreview=\"{}\" error={}; using full role tool set",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    exception.getClass().getSimpleName(),
                    exception);
            return fullRoleTools;
        }
    }

    private void logToolSelectorUnavailable(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<Object> fullRoleTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool selector unavailable role={} permissionCodes={} resolvedRoleTools={} queryPreview=\"{}\"",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.toolNames(fullRoleTools),
                    sharedOrchestrationSupport.preview(message));
        }
    }

    private void logWorkflowState(@NonNull String message, @NonNull String workflowState) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared workflow state derived message preview=\"{}\" workflowState={}",
                    sharedOrchestrationSupport.preview(message),
                    workflowState);
        }
    }

    private void logCandidates(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String workflowState,
            @NonNull List<ToolMetadata> candidates) {
        if (LOGGER.isDebugEnabled()) {
            for (int i = 0; i < candidates.size(); i++) {
                ToolMetadata candidate = candidates.get(i);
                double confidence = confidenceScore(i, candidate.priority());
                LOGGER.debug(
                        "MCP shared tool candidate role={} permissionCodes={} workflowState={} toolName={} score={} priority={}",
                        role,
                        permissionCodes,
                        workflowState,
                        candidate.name(),
                        String.format(Locale.ROOT, "%.3f", confidence),
                        String.format(Locale.ROOT, "%.3f", candidate.priority()));
            }
        }
    }

    private @NonNull List<Object> logNoCandidates(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<Object> fullRoleTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool selector returned no candidates role={} permissionCodes={} queryPreview=\"{}\" fullRoleTools={}; using full role tool set",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    sharedOrchestrationSupport.toolNames(fullRoleTools));
        }
        return fullRoleTools;
    }

    private @NonNull List<Object> logResolvedToZeroTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<String> selectedNames,
            @NonNull List<Object> fullRoleTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool candidates resolved to zero role tools role={} permissionCodes={} queryPreview=\"{}\" candidateNames={} fullRoleTools={}; using full role tool set",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    selectedNames,
                    sharedOrchestrationSupport.toolNames(fullRoleTools));
        }
        return fullRoleTools;
    }

    private void logResolvedCandidates(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<String> selectedNames,
            @NonNull List<Object> resolvedTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool candidates role={} permissionCodes={} queryPreview=\"{}\" candidateNames={} resolvedRoleTools={}",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    selectedNames,
                    sharedOrchestrationSupport.toolNames(resolvedTools));
        }
    }

    /**
     * Heuristic workflow-state derivation from message text. Used only as a fallback for
     * session-less callers; the authoritative state is the persisted {@code NltiSession} value
     * supplied to the {@code WorkflowState} overload of {@link #selectRoleTools}.
     */
    private @NonNull WorkflowState deriveWorkflowState(@NonNull String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, Set.of("purchase order", "create po", "new po", "po for vendor"))) {
            return WorkflowState.CREATING_PO;
        } else if (containsAny(
                lower,
                Set.of(
                        "receiving asn",
                        "receive asn",
                        "receive shipment",
                        "receive order",
                        "receiving shipment",
                        "advanced shipment notice",
                        "asn"))) {
            return WorkflowState.RECEIVING_ASN;
        } else if (containsAny(
                lower,
                Set.of(
                        "inventory recon",
                        "inventory reconciliation",
                        "reconcile inventory",
                        "stock reconciliation",
                        "cycle count"))) {
            return WorkflowState.INVENTORY_RECON;
        }
        return WorkflowState.IDLE;
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MCP shared fallback tool matches tools={}", sharedOrchestrationSupport.toolNames(selected));
        }
        return selected;
    }

    private static boolean containsAny(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (token.contains(" ")) {
                if (text.contains(token)) {
                    return true;
                }
            } else if (text.matches(".*\\b" + token + "\\b.*")) {
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
            @NonNull List<Object> roleTools, @NonNull List<Object> fallbackTools) {}
}
