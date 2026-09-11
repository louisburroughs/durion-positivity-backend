package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import com.positivity.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies the bound tenant's tool-priority overlay to catalog rows (ADR-0062 plan WS6).
 *
 * <p>{@code mcp_tool} carries the global priority of every tool. A tenant whose invocation history
 * has been tuned has an overlay row per tuned tool, and for a request bound to that tenant the
 * overlay wins tool by tool: a tool without an overlay row keeps the global priority, and a tenant
 * with no overlay at all (no invocation history yet, or tuning never run live) gets the global set
 * unchanged. The overlay is read once per resolution through the bound connection, so the rows it
 * sees are the bound tenant's alone (row-level security); unbound, it sees none and nothing changes.
 */
@Service
public class TenantToolPriorityResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantToolPriorityResolver.class);

    private final ToolPriorityRepository repository;

    public TenantToolPriorityResolver(@NonNull ToolPriorityRepository repository) {
        this.repository = repository;
    }

    /** A snapshot of the bound tenant's overlay, to apply to several lists in one resolution. */
    public @NonNull Overlay currentOverlay() {
        Map<UUID, ToolPriorityOverlay> rows = repository.findOverlayForCurrentTenant();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool priority overlay tenant={} overlayRows={}",
                    TenantContext.current().map(UUID::toString).orElse("-"),
                    rows.size());
        }
        return new Overlay(rows);
    }

    /** Convenience for a single list: {@code currentOverlay().apply(tools)}. */
    public @NonNull List<ToolMetadata> apply(@NonNull List<ToolMetadata> tools) {
        return currentOverlay().apply(tools);
    }

    /** The bound tenant's overlay rows, applied tool by tool with the global row as the fallback. */
    public static final class Overlay {

        private final Map<UUID, ToolPriorityOverlay> rows;

        Overlay(@NonNull Map<UUID, ToolPriorityOverlay> rows) {
            this.rows = Map.copyOf(rows);
        }

        /** True when the tenant has no tuned tool: every tool falls back to the global set. */
        public boolean isEmpty() {
            return rows.isEmpty();
        }

        /** The input, with the priority and latency of every overlaid tool replaced; order unchanged. */
        public @NonNull List<ToolMetadata> apply(@NonNull List<ToolMetadata> tools) {
            if (rows.isEmpty() || tools.isEmpty()) {
                return tools;
            }
            List<ToolMetadata> resolved = new ArrayList<>(tools.size());
            for (ToolMetadata tool : tools) {
                ToolPriorityOverlay overlay = rows.get(tool.id());
                resolved.add(overlay == null ? tool : withOverlay(tool, overlay));
            }
            return List.copyOf(resolved);
        }

        private static @NonNull ToolMetadata withOverlay(
                @NonNull ToolMetadata tool, @NonNull ToolPriorityOverlay overlay) {
            return new ToolMetadata(
                    tool.id(),
                    tool.name(),
                    tool.displayName(),
                    tool.description(),
                    tool.domain(),
                    overlay.priority(),
                    tool.costLevel(),
                    overlay.avgLatencyMs(),
                    tool.enabled(),
                    tool.handlerBean());
        }
    }
}
