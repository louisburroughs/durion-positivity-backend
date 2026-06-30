package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.mcp.service.RolePromptResolver.AssembledPrompt;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 1: layered, role-first prompt assembly + persona seed coverage. */
class RolePromptAssemblyTest {

    private SystemPrompt prompt(String name, String content) {
        SystemPrompt p = new SystemPrompt();
        p.setName(name);
        p.setContent(content);
        return p;
    }

    @Test
    @DisplayName("seeds all 9 internal role personas; never customer personas")
    void seedCoverage() {
        var seeds = SystemPromptSeedRunner.seedPrompts();
        assertThat(seeds)
                .containsKeys(
                        SystemPromptDefaults.MASTER_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_SERVICE_ADVISOR_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_TECHNICIAN_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_DISPATCHER_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_LOCATION_MANAGER_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_ACCOUNT_MANAGER_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_ACCOUNTING_ASSOCIATE_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_ADMIN_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_SYSTEM_ADMINISTRATOR_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_USER_PROMPT_NAME);
        assertThat(seeds)
                .doesNotContainKeys(
                        SystemPromptDefaults.ROLE_CUSTOMER_PROMPT_NAME,
                        SystemPromptDefaults.ROLE_SELF_SERVICE_CUSTOMER_PROMPT_NAME);
        // Persona must not assert access semantics (Prompt lock).
        assertThat(seeds.get(SystemPromptDefaults.ROLE_TECHNICIAN_PROMPT_NAME)).contains("never grants access");
    }

    @Test
    @DisplayName("assemble composes BASE + ROLE + DOMAIN + TOOL_USE in order")
    void assembleAllLayers() {
        SystemPromptRepository repo = mock(SystemPromptRepository.class);
        when(repo.findByName(SystemPromptDefaults.MASTER_PROMPT_NAME))
                .thenReturn(Optional.of(prompt("master", "BASE_TEXT")));
        when(repo.findByName("ROLE_TECHNICIAN")).thenReturn(Optional.of(prompt("ROLE_TECHNICIAN", "ROLE_TEXT")));
        when(repo.findByName("accounting")).thenReturn(Optional.of(prompt("accounting", "DOMAIN_TEXT")));
        var resolver = new RolePromptResolverImpl(repo);

        AssembledPrompt out = resolver.assemble("ROLE_TECHNICIAN", "accounting");

        assertThat(out.layers()).containsExactly("BASE", "ROLE", "DOMAIN", "TOOL_USE");
        assertThat(out.text()).contains("BASE_TEXT").contains("ROLE_TEXT").contains("DOMAIN_TEXT");
        // order: BASE before ROLE before DOMAIN
        assertThat(out.text().indexOf("BASE_TEXT")).isLessThan(out.text().indexOf("ROLE_TEXT"));
        assertThat(out.text().indexOf("ROLE_TEXT")).isLessThan(out.text().indexOf("DOMAIN_TEXT"));
    }

    @Test
    @DisplayName("unseeded role -> ROLE layer skipped; master scope -> DOMAIN skipped")
    void assembleSkipsMissingLayers() {
        SystemPromptRepository repo = mock(SystemPromptRepository.class);
        when(repo.findByName(SystemPromptDefaults.MASTER_PROMPT_NAME))
                .thenReturn(Optional.of(prompt("master", "BASE_TEXT")));
        lenient().when(repo.findByName("ROLE_UNSEEDED")).thenReturn(Optional.empty());
        var resolver = new RolePromptResolverImpl(repo);

        AssembledPrompt out = resolver.assemble("ROLE_UNSEEDED", "master");

        assertThat(out.layers()).containsExactly("BASE", "TOOL_USE");
        assertThat(out.text()).contains("BASE_TEXT");
    }

    @Test
    @DisplayName("falls back to built-in BASE when master prompt is unseeded")
    void assembleBuiltInBaseFallback() {
        SystemPromptRepository repo = mock(SystemPromptRepository.class);
        when(repo.findByName(SystemPromptDefaults.MASTER_PROMPT_NAME)).thenReturn(Optional.empty());
        lenient().when(repo.findByName("ROLE_USER")).thenReturn(Optional.empty());
        var resolver = new RolePromptResolverImpl(repo);

        AssembledPrompt out = resolver.assemble("ROLE_USER", "master");

        assertThat(out.layers()).containsExactly("BASE", "TOOL_USE");
        assertThat(out.text()).contains("master orchestration agent"); // from built-in DEFAULT_PROMPT_TEXT
    }
}
