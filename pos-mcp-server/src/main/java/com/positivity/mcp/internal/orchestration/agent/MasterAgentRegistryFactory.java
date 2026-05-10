package com.positivity.mcp.internal.orchestration.agent;

import com.positivity.mcp.internal.service.MasterAgentRegistryLoader;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class MasterAgentRegistryFactory {

    private final MasterAgentRegistryLoader loader;

    @Autowired
    public MasterAgentRegistryFactory(@NonNull MasterAgentRegistryLoader loader) {
        this.loader = loader;
    }

    public @NonNull LoadedMasterAgentRegistry loadRegistryDefinition() {
        MasterAgentRegistryLoader.LoadedMasterAgentRegistry loadedRegistry = loader.loadRegistryDefinition();
        List<DomainAgentDefinition> domainAgents = loadedRegistry.domainToolAssignments().entrySet().stream()
                .map(entry -> new DomainAgentDefinition(entry.getKey(), entry.getKey(), entry.getValue()))
                .toList();
        return new LoadedMasterAgentRegistry(
                loadedRegistry.sharedTools(), domainAgents, loadedRegistry.roleToolAssignments());
    }

    public record LoadedMasterAgentRegistry(
            @NonNull List<Object> sharedTools,
            @NonNull List<DomainAgentDefinition> domainAgents,
            @NonNull Map<String, List<Object>> roleToolAssignments) {

        public LoadedMasterAgentRegistry(@NonNull List<Object> sharedTools, @NonNull List<DomainAgentDefinition> domainAgents) {
            this(sharedTools, domainAgents, Map.of());
        }
    }
}
