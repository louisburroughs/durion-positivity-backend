package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.RoleAuthorities;
import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.domain.RolePersonaSnapshot;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.mcp.internal.service.RolePromptResolver.AssembledPrompt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
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

    /**
     * #1613 inverted this: the seed runner used to hardcode nine role personas, which is why seven
     * roles that existed in pos-security-service had none at all. Role personas are synced now, so
     * the only one that may be seeded here is the ROLE_USER fallback — it has no upstream row.
     *
     * <p>Asserting the absence is the point: a hardcoded persona reintroduced here would work for
     * whoever added it and quietly resume the drift for everyone else.
     */
    @Test
    @DisplayName("seeds only the ROLE_USER fallback persona; every other role persona is synced")
    void seedCoverage() {
        var seeds = SystemPromptSeedRunner.seedPrompts();

        assertThat(seeds)
                .containsKeys(SystemPromptDefaults.MASTER_PROMPT_NAME, SystemPromptDefaults.ROLE_USER_PROMPT_NAME);
        assertThat(seeds.keySet())
                .filteredOn(name -> name.startsWith(RoleAuthorities.ROLE_PREFIX))
                .containsExactly(SystemPromptDefaults.ROLE_USER_PROMPT_NAME);
        // Persona must not assert access semantics (Prompt lock).
        assertThat(seeds.get(SystemPromptDefaults.ROLE_USER_PROMPT_NAME)).contains("never grants access");
    }

    @Test
    @DisplayName("assemble composes BASE + ROLE + DOMAIN + TOOL_USE in order")
    void assembleAllLayers() {
        SystemPromptRepository repo = mock(SystemPromptRepository.class);
        when(repo.findByName(SystemPromptDefaults.MASTER_PROMPT_NAME))
                .thenReturn(Optional.of(prompt("master", "BASE_TEXT")));
        when(repo.findByName("ROLE_TECHNICIAN")).thenReturn(Optional.of(prompt("ROLE_TECHNICIAN", "ROLE_TEXT")));
        when(repo.findByName("accounting")).thenReturn(Optional.of(prompt("accounting", "DOMAIN_TEXT")));
        var resolver = TestSnapshots.resolver(repo, new SimpleMeterRegistry());

        AssembledPrompt out = resolver.assemble("ROLE_TECHNICIAN", "accounting");

        assertThat(out.layers()).containsExactly("BASE", "ROLE", "DOMAIN", "TOOL_USE", "DATE_WINDOW", "GLOSSARY");
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
        var resolver = TestSnapshots.resolver(repo, new SimpleMeterRegistry());

        AssembledPrompt out = resolver.assemble("ROLE_UNSEEDED", "master");

        assertThat(out.layers()).containsExactly("BASE", "TOOL_USE", "DATE_WINDOW", "GLOSSARY");
        assertThat(out.text()).contains("BASE_TEXT");
    }

    @Test
    @DisplayName("falls back to built-in BASE when master prompt is unseeded")
    void assembleBuiltInBaseFallback() {
        SystemPromptRepository repo = mock(SystemPromptRepository.class);
        when(repo.findByName(SystemPromptDefaults.MASTER_PROMPT_NAME)).thenReturn(Optional.empty());
        lenient().when(repo.findByName("ROLE_USER")).thenReturn(Optional.empty());
        var resolver = TestSnapshots.resolver(repo, new SimpleMeterRegistry());

        AssembledPrompt out = resolver.assemble("ROLE_USER", "master");

        assertThat(out.layers()).containsExactly("BASE", "TOOL_USE", "DATE_WINDOW", "GLOSSARY");
        assertThat(out.text()).contains("master orchestration agent"); // from built-in DEFAULT_PROMPT_TEXT
    }

    /**
     * Gate 2A (#639) still holds, but the covered set is now the synced one rather than a
     * compile-time list (#1613): every role a caller can resolve to gets a warm agent, capped.
     */
    @Test
    @DisplayName("Gate 2A: preload role set follows the synced snapshot and always includes ROLE_USER")
    void preloadCoverage() {
        RolePersonaSnapshot snapshot = RolePersonaSnapshot.of(
                Instant.EPOCH,
                List.of(
                        new RolePersona("TECHNICIAN", null, null, null, null, (short) 80, true),
                        new RolePersona("ADMIN", null, null, null, null, (short) 20, true)));

        assertThat(snapshot.preloadableRoleIdentifiers(16)).containsExactly("ROLE_ADMIN", "ROLE_TECHNICIAN");
    }
}
