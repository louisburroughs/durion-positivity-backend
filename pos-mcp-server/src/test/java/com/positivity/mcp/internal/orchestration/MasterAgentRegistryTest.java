package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import com.positivity.mcp.internal.orchestration.agent.DomainAgentDefinition;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistryFactory;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.service.RolePersonaSnapshotHolder;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class MasterAgentRegistryTest {

    @Mock
    private MasterAgentRegistryFactory registryFactory;

    /** A holder that has never been synced, for tests that do not exercise warm-up. */
    private static RolePersonaSnapshotHolder emptyHolder() {
        return new RolePersonaSnapshotHolder(new SimpleMeterRegistry(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void constructorFromLoaderPreservesSharedAndDomainRuntimeSplit() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        when(registryFactory.loadRegistryDefinition())
                .thenReturn(new MasterAgentRegistryFactory.LoadedMasterAgentRegistry(
                        List.of(sharedTool),
                        List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)))));

        MasterAgentRegistry registry = new MasterAgentRegistry(registryFactory, emptyHolder(), 16);

        assertThat(registry.sharedTools()).containsExactly(sharedTool);
        assertThat(registry.domainAgents())
                .extracting(DomainAgentDefinition::agentName)
                .containsExactly("inventory");
        assertThat(registry.resolveMasterTools()).containsExactly(sharedTool);
        assertThat(registry.resolveDomainTools("inventory")).containsExactly(inventoryTool);
        // Gate 2B / #780: role names are not domain agents and resolve nothing (no role preassignment).
        assertThat(registry.resolveDomainTools("ROLE_MANAGER")).isEmpty();
    }

    @Test
    void resolveMasterToolsReturnsMutableCopy() {
        Object sharedTool = new SharedToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool), List.of(new DomainAgentDefinition("inventory", "inventory", List.of())));

        List<Object> firstCall = registry.resolveMasterTools();
        firstCall.add(new Object());

        List<Object> secondCall = registry.resolveMasterTools();

        assertThat(secondCall).hasSize(1);
        assertSame(sharedTool, secondCall.getFirst());
    }

    @Test
    void resolveDomainToolsReturnsMutableCopy() {
        Object domainTool = new Object();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(), List.of(new DomainAgentDefinition("inventory", "inventory", List.of(domainTool))));

        List<Object> firstCall = registry.resolveDomainTools("inventory");
        firstCall.add(new Object());

        List<Object> secondCall = registry.resolveDomainTools("inventory");

        assertThat(secondCall).hasSize(1);
        assertSame(domainTool, secondCall.getFirst());
    }

    @Test
    void resolveToolsByNameSearchesAllDomainsIgnoringRoleScope() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        Object orderTool = new OrderFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)),
                        new DomainAgentDefinition("orders", "orders", List.of(orderTool))));

        // Regression (facade tool binding): permission gating + scoring run upstream, so the
        // name->bean step must search the full registered set across every domain, not role-scoped.
        assertThat(registry.resolveToolsByName(
                        List.of("orderFacadeToolStub", "inventoryFacadeToolStub", "sharedToolStub")))
                .containsExactlyInAnyOrder(orderTool, inventoryTool, sharedTool);
    }

    @Test
    void resolveToolsByNameReturnsEmptyForEmptySelection() {
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(new SharedToolStub()),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(new InventoryFacadeToolStub()))));

        assertThat(registry.resolveToolsByName(List.of())).isEmpty();
    }

    @Test
    void preloadableDomainAgentsReturnsAgentNames() {
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of()),
                        new DomainAgentDefinition("orders", "orders", List.of())));

        assertThat(registry.preloadableDomainAgents()).containsExactly("inventory", "orders");
    }

    @Test
    void preloadableRoleIdentifiersFollowsTheSyncedSnapshot() {
        Object inventoryTool = new InventoryFacadeToolStub();
        RolePersonaSnapshot snapshot = RolePersonaSnapshot.of(
                Instant.EPOCH,
                List.of(
                        new RolePersona("TECHNICIAN", null, null, null, null, (short) 80, true),
                        new RolePersona("ADMIN", null, null, null, null, (short) 20, true),
                        // Ineligible roles never get an agent prebuilt: their callers land on the
                        // fallback by design, so a warm agent for them would be wasted.
                        new RolePersona("CUSTOMER", null, null, null, null, null, false)));
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))),
                () -> snapshot,
                16);

        // Gate 2A (#639): every role a caller can resolve to gets a warm agent. #1613: that set is
        // the synced snapshot rather than a compile-time list, plus the ROLE_USER fallback, which has
        // no upstream row.
        assertThat(registry.preloadableRoleIdentifiers())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TECHNICIAN", SystemPromptDefaults.ROLE_USER_PROMPT_NAME);
    }

    @Test
    void preloadableRoleIdentifiersHonoursTheCapSoManyRolesCannotBlowUpPrebuild() {
        RolePersonaSnapshot snapshot = RolePersonaSnapshot.of(
                Instant.EPOCH,
                List.of(
                        new RolePersona("ADMIN", null, null, null, null, (short) 10, true),
                        new RolePersona("MANAGER", null, null, null, null, (short) 20, true),
                        new RolePersona("TECHNICIAN", null, null, null, null, (short) 30, true)));
        MasterAgentRegistry registry = new MasterAgentRegistry(List.of(), List.of(), () -> snapshot, 2);

        // The cap keeps the highest-ranked roles — the ones a caller is most likely to resolve to.
        assertThat(registry.preloadableRoleIdentifiers())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_MANAGER", SystemPromptDefaults.ROLE_USER_PROMPT_NAME);
    }

    @Test
    void resolveRagScopeForToolsReturnsMasterWhenSharedAndDomainToolsMixed() {
        Object sharedTool = new SharedToolStub();
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(sharedTool),
                List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))));

        assertThat(registry.resolveRagScopeForTools(List.of(sharedTool, inventoryTool)))
                .isEqualTo("master");
    }

    @Test
    void resolveRagScopeForToolsReturnsMasterWhenUnregisteredFallbackAndDomainToolsMixed() {
        ExaWebSearchTool exaWebSearchTool =
                new ExaWebSearchTool(RestClient.builder(), "https://api.exa.ai", "", "auto", 5);
        Object inventoryTool = new InventoryFacadeToolStub();
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(), List.of(new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool))));

        assertThat(registry.resolveRagScopeForTools(List.of(inventoryTool, exaWebSearchTool)))
                .isEqualTo("master");
    }

    /**
     * #1684. `DateWindowFacadeTool` joins the merged tool set on any dated question, and it sits in
     * a domain agent of its own (`V43` gives its `mcp_tool` row `domain='date-window'`, and
     * `MasterAgentRegistryLoader` turns every non-shared domain into one). Counted as a domain vote
     * it would widen an otherwise single-domain retrieval to `master` on every dated question —
     * a silent change to what RAG returns, made by a tool that retrieves nothing.
     */
    @Test
    void resolveRagScopeForToolsIgnoresTheDateWindowToolWhenScopingASingleDomain() {
        Object inventoryTool = new InventoryFacadeToolStub();
        DateWindowFacadeTool dateWindowTool = new DateWindowFacadeTool(Clock.systemUTC());
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(),
                List.of(
                        new DomainAgentDefinition("inventory", "inventory", List.of(inventoryTool)),
                        new DomainAgentDefinition("date-window", "date-window", List.of(dateWindowTool))));

        assertThat(registry.resolveRagScopeForTools(List.of(inventoryTool, dateWindowTool)))
                .isEqualTo("inventory");
    }

    /**
     * The fail-closed path (#1606): an empty gated set yields no role tools, so on a dated question
     * the merged set is the date-window tool alone. Scoped off that single tool the answer would be
     * the literal string "date-window" — a scope no `rag_document` row carries, which
     * `RagScope.normalize` passes through unchanged and which then filters retrieval down to
     * master-only rows instead of searching every scope.
     */
    @Test
    void resolveRagScopeForToolsReturnsMasterWhenOnlyTheDateWindowToolIsSelected() {
        DateWindowFacadeTool dateWindowTool = new DateWindowFacadeTool(Clock.systemUTC());
        MasterAgentRegistry registry = new MasterAgentRegistry(
                List.of(), List.of(new DomainAgentDefinition("date-window", "date-window", List.of(dateWindowTool))));

        assertThat(registry.resolveRagScopeForTools(List.of(dateWindowTool))).isEqualTo("master");
    }

    private static final class SharedToolStub {}

    private static final class InventoryFacadeToolStub {}

    private static final class OrderFacadeToolStub {}
}
