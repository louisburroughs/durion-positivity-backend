package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * DB-backed tool registry loader. Replaces Phase 1 in-memory stub.
 * Loads all enabled tools for each known role from the mcp_tool database tables
 * and resolves them to Spring bean instances via ApplicationContext.
 */
@Component
public class ToolRegistryLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryLoader.class);

    private static final String DEFAULT_PRELOAD_WORKFLOW_STATE = "IDLE";

    private final ToolMetadataRepository repository;
    private final ApplicationContext applicationContext;
    private final String preloadWorkflowState;

    ToolRegistryLoader(@NonNull ToolMetadataRepository repository, @NonNull ApplicationContext applicationContext) {
        this(repository, applicationContext, DEFAULT_PRELOAD_WORKFLOW_STATE);
    }

    @Autowired
    public ToolRegistryLoader(
            @NonNull ToolMetadataRepository repository,
            @NonNull ApplicationContext applicationContext,
            @Value("${mcp.agent.preload-workflow-state:IDLE}") @NonNull String preloadWorkflowState) {
        this.repository = repository;
        this.applicationContext = applicationContext;
        this.preloadWorkflowState = sanitizeWorkflowState(preloadWorkflowState);
    }

    /**
     * Loads role-to-tool-instance mappings from the DB at startup for the IDLE
     * workflow state.
     * Only tools mapped to the {@code IDLE} workflow state are preloaded; tools for
     * other
     * workflow states (e.g., CREATING_PO) are resolved dynamically per request.
     * Tools are resolved by their Spring bean name (handler_bean column).
     */
    public @NonNull Map<String, List<Object>> loadRoleToolMappings() {
        String workflowState = resolvePreloadWorkflowState();
        List<String> roles = repository.findAllRoleNames();
        if (roles.isEmpty()) {
            log.warn("No roles found in mcp_role table; tool registry will be empty");
        }
        Map<String, List<Object>> result = new HashMap<>();
        for (String role : roles) {
            List<ToolMetadata> tools = repository.findEnabledByRoleAndWorkflow(role, workflowState);
            List<Object> beans = new ArrayList<>();
            for (ToolMetadata meta : tools) {
                try {
                    Object bean = applicationContext.getBean(meta.handlerBean());
                    beans.add(bean);
                } catch (NoSuchBeanDefinitionException e) {
                    log.warn(
                            "Tool bean '{}' not found for role '{}', skipping: {}",
                            meta.handlerBean(),
                            role,
                            e.getMessage());
                }
            }
            result.put(role, beans);
        }
        return result;
    }

    private @NonNull String resolvePreloadWorkflowState() {
        return preloadWorkflowState;
    }

    private static @NonNull String sanitizeWorkflowState(@NonNull String workflowState) {
        String normalized = workflowState.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.isBlank() ? DEFAULT_PRELOAD_WORKFLOW_STATE : normalized;
    }
}
