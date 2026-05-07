package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RolePromptResolverImpl}.
 */
@ExtendWith(MockitoExtension.class)
class RolePromptResolverImplTest {

    private static final String ROLE_NAME = "ROLE_CASHIER";
    private static final String DEFAULT_NAME = "default";

    @Mock
    private SystemPromptRepository systemPromptRepository;

    @InjectMocks
    private RolePromptResolverImpl resolver;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static SystemPrompt buildPrompt(String name, String content) {
        var p = new SystemPrompt();
        p.setName(name);
        p.setContent(content);
        return p;
    }

    // ─── resolvePrompt ──────────────────────────────────────────────────────

    @Test
    @DisplayName("resolvePrompt returns role content when role name exists")
    void resolvePrompt_roleExists_returnsRoleContent() {
        SystemPrompt rolePrompt = buildPrompt(ROLE_NAME, "You are a cashier assistant.");
        when(systemPromptRepository.findByName(ROLE_NAME)).thenReturn(Optional.of(rolePrompt));

        String result = resolver.resolvePrompt(ROLE_NAME);

        assertThat(result).isEqualTo("You are a cashier assistant.");
    }

    @Test
    @DisplayName("resolvePrompt returns default content when role not found but default exists")
    void resolvePrompt_roleNotFound_defaultExists_returnsDefaultContent() {
        SystemPrompt defaultPrompt = buildPrompt(DEFAULT_NAME, "You are a default assistant.");
        when(systemPromptRepository.findByName(ROLE_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(DEFAULT_NAME)).thenReturn(Optional.of(defaultPrompt));

        String result = resolver.resolvePrompt(ROLE_NAME);

        assertThat(result).isEqualTo("You are a default assistant.");
    }

    @Test
    @DisplayName("resolvePrompt returns built-in prompt when neither role nor default exists")
    void resolvePrompt_neitherFound_returnsBuiltIn() {
        when(systemPromptRepository.findByName(ROLE_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(DEFAULT_NAME)).thenReturn(Optional.empty());

        String result = resolver.resolvePrompt(ROLE_NAME);

        assertThat(result).contains("concise POS assistant");
    }

    @Test
    @DisplayName("resolvePrompt returns shared default text when neither role nor default prompt exists")
    void resolvePrompt_noPromptFoundForRoleOrDefault_returnsSharedDefaultText() {
        when(systemPromptRepository.findByName(ROLE_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(DEFAULT_NAME)).thenReturn(Optional.empty());

        String result = resolver.resolvePrompt(ROLE_NAME);

        assertThat(result).isEqualTo(SystemPromptDefaults.DEFAULT_PROMPT_TEXT);
    }
}
