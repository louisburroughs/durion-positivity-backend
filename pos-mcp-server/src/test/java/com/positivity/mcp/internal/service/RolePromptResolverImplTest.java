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

    private static final String AGENT_NAME = "inventory";
    private static final String MASTER_NAME = "master";

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
    @DisplayName("resolvePrompt returns agent content when agent prompt exists")
    void resolvePrompt_agentPromptExists_returnsAgentContent() {
        SystemPrompt agentPrompt = buildPrompt(AGENT_NAME, "You are the inventory domain agent.");
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.of(agentPrompt));

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).isEqualTo("You are the inventory domain agent.");
    }

    @Test
    @DisplayName("resolvePrompt falls back to master prompt when agent prompt is missing")
    void resolvePrompt_agentPromptMissing_masterExists_returnsMasterContent() {
        SystemPrompt masterPrompt = buildPrompt(MASTER_NAME, "You are the master orchestration agent.");
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.of(masterPrompt));

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).isEqualTo("You are the master orchestration agent.");
    }

    @Test
    @DisplayName("resolvePrompt returns built-in prompt when neither agent nor master prompt exists")
    void resolvePrompt_neitherFound_returnsBuiltIn() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.empty());

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).contains("concise POS assistant");
    }

    @Test
    @DisplayName("resolvePrompt returns shared default text when neither agent nor master prompt exists")
    void resolvePrompt_noPromptFoundForAgentOrMaster_returnsSharedDefaultText() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.empty());

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).isEqualTo(SystemPromptDefaults.DEFAULT_PROMPT_TEXT);
    }
}
