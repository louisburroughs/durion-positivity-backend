package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private SimpleMeterRegistry meterRegistry;
    private RolePromptResolverImpl resolver;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        resolver = new RolePromptResolverImpl(systemPromptRepository, meterRegistry);
    }

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

    // ─── fallback observability (#639) ──────────────────────────────────────

    @Test
    @DisplayName("resolvePrompt increments no fallback counter when the requested prompt exists")
    void resolvePrompt_promptExists_recordsNoFallback() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.of(buildPrompt(AGENT_NAME, "content")));

        resolver.resolvePrompt(AGENT_NAME);

        assertThat(meterRegistry
                        .find(RolePromptResolverImpl.METRIC_PROMPT_FALLBACK)
                        .counters())
                .isEmpty();
    }

    @Test
    @DisplayName("resolvePrompt counts a master-prompt fallback when the requested prompt is missing")
    void resolvePrompt_agentPromptMissing_recordsMasterFallbackMetric() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));

        resolver.resolvePrompt(AGENT_NAME);

        assertThat(meterRegistry
                        .counter(
                                RolePromptResolverImpl.METRIC_PROMPT_FALLBACK,
                                "reason",
                                RolePromptResolverImpl.REASON_MASTER_PROMPT,
                                "requested",
                                AGENT_NAME)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("resolvePrompt counts a built-in fallback when neither prompt exists")
    void resolvePrompt_neitherFound_recordsBuiltInFallbackMetric() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.empty());

        resolver.resolvePrompt(AGENT_NAME);

        assertThat(meterRegistry
                        .counter(
                                RolePromptResolverImpl.METRIC_PROMPT_FALLBACK,
                                "reason",
                                RolePromptResolverImpl.REASON_BUILT_IN,
                                "requested",
                                AGENT_NAME)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("assemble counts a missing-role-layer fallback when the role persona is unseeded")
    void assemble_rolePersonaMissing_recordsMissingRoleLayerMetric() {
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));
        when(systemPromptRepository.findByName("ROLE_TECHNICIAN")).thenReturn(Optional.empty());

        resolver.assemble("ROLE_TECHNICIAN", "master");

        assertThat(meterRegistry
                        .counter(
                                RolePromptResolverImpl.METRIC_PROMPT_FALLBACK,
                                "reason",
                                RolePromptResolverImpl.REASON_MISSING_ROLE_LAYER,
                                "requested",
                                "ROLE_TECHNICIAN")
                        .count())
                .isEqualTo(1.0);
    }
}
