package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.mcp.internal.service.RolePromptResolver.AssembledPrompt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate 6 (#1193): the WRITE-GATE prompt layer is appended only when a write-capable tool is in the
 * candidate set, and the two-arg assemble overload keeps the legacy (no WRITE-GATE) behaviour.
 */
class WriteGatePromptLayerTest {

    private SystemPromptRepository repository;
    private RolePromptResolverImpl resolver;

    @BeforeEach
    void setUp() {
        repository = mock(SystemPromptRepository.class);
        when(repository.findByName(anyString())).thenReturn(Optional.empty());
        resolver = TestSnapshots.resolver(repository, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("assemble with write-capable candidates appends the WRITE_GATE layer last")
    void assemble_withWriteCapableTools_appendsWriteGateLayer() {
        AssembledPrompt prompt = resolver.assemble("ROLE_SERVICE_ADVISOR", "master", true);

        assertThat(prompt.layers()).endsWith("TOOL_USE", "WRITE_GATE");
        assertThat(prompt.text()).contains("Write-action gate:");
        assertThat(prompt.text()).contains("explicit user confirmation");
    }

    @Test
    @DisplayName("assemble without write-capable candidates omits the WRITE_GATE layer")
    void assemble_withoutWriteCapableTools_omitsWriteGateLayer() {
        AssembledPrompt prompt = resolver.assemble("ROLE_SERVICE_ADVISOR", "master", false);

        assertThat(prompt.layers()).isNotEmpty().doesNotContain("WRITE_GATE");
        assertThat(prompt.text()).doesNotContain("Write-action gate:");
    }

    @Test
    @DisplayName("legacy two-arg assemble stays WRITE_GATE-free (managers not yet passing the flag)")
    void assemble_twoArgOverload_hasNoWriteGate() {
        AssembledPrompt prompt = resolver.assemble("ROLE_SERVICE_ADVISOR", "master");

        assertThat(prompt.layers()).isNotEmpty().doesNotContain("WRITE_GATE");
    }
}
