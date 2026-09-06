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

        assertThat(prompt.layers()).endsWith("TOOL_USE", "DATE_WINDOW", "GLOSSARY", "WRITE_GATE");
        assertThat(prompt.text()).contains("Write-action gate:");
        assertThat(prompt.text()).contains("explicit user confirmation");
    }

    @Test
    @DisplayName(
            "the write gate exempts reads, keeps a write a write however phrased, and says so above its precedence "
                    + "line (#1821)")
    void writeGate_exemptsReads_aboveThePrecedenceLine() {
        // q04 was answered twice with "Would you like me to proceed?" and no tool call — this layer's
        // confirmation habit leaking onto a fully specified read. Pinned on the constant, not on the
        // assembled text, so it survives the bullet moving within the layer.
        String layer = SystemPromptDefaults.WRITE_GATE_LAYER_TEXT;
        int exemption = layer.indexOf("Reads — lookups and reports that change nothing — are NOT gated");
        int precedence = layer.indexOf("These rules take precedence");

        assertThat(exemption).isNotNegative();
        assertThat(layer).contains("never ask whether to proceed").contains("is a write however it is phrased");
        assertThat(exemption).isLessThan(precedence);
    }

    @Test
    @DisplayName("the always-present tool-use layer carries the by-period composition rule (#1821)")
    void toolUse_carriesTheByPeriodRule() {
        // The write gate is only appended when a write-capable tool is a candidate, so the
        // composition guidance a by-month read needs must not depend on it.
        assertThat(SystemPromptDefaults.TOOL_USE_LAYER_TEXT)
                .contains("one call per period against the same tool")
                .contains("never ask whether to proceed");
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
