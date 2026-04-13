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

  private static final String WORKFLOW_IDLE = "IDLE";

  private static final List<String> KNOWN_ROLES = List.of(
      "ROLE_CASHIER", "ROLE_SERVICE_WRITER",
      "ROLE_MANAGER", "ROLE_ADMIN", "ROLE_SUPPLIER");

  private final ToolMetadataRepository repository;
  private final ApplicationContext applicationContext;

  public ToolRegistryLoader(
      @NonNull ToolMetadataRepository repository,
      @NonNull ApplicationContext applicationContext) {
    this.repository = repository;
    this.applicationContext = applicationContext;
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
    Map<String, List<Object>> result = new HashMap<>();
    for (String role : KNOWN_ROLES) {
      List<ToolMetadata> tools = repository.findEnabledByRoleAndWorkflow(role, WORKFLOW_IDLE);
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
}
