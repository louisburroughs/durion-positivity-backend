package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SystemPromptDefaults}: prompt name resolution and delegation
 * to RagScope normalization.
 *
 * <p>
 * Tests verify that:
 * <ul>
 * <li>promptNameForRagScope() delegates to RagScope.normalize()</li>
 * <li>"shared" scope is correctly mapped to MASTER_PROMPT_NAME</li>
 * <li>Edge cases (null, blank, mixed case, trimming) are handled</li>
 * </ul>
 */
class SystemPromptDefaultsTest {

    @Test
    @DisplayName("promptNameForRagScope delegates to RagScope.normalize for non-shared scopes")
    void promptNameForRagScope_delegatesToRagScopeNormalize() {
        // RagScope.normalize() lowercases and trims
        assertThat(SystemPromptDefaults.promptNameForRagScope("INVENTORY")).isEqualTo("inventory");
        assertThat(SystemPromptDefaults.promptNameForRagScope("ORDER")).isEqualTo("order");
        assertThat(SystemPromptDefaults.promptNameForRagScope("CataLog")).isEqualTo("catalog");
    }

    @Test
    @DisplayName("promptNameForRagScope handles leading/trailing whitespace")
    void promptNameForRagScope_handlesWhitespace() {
        // RagScope.normalize() trims before lowercasing
        assertThat(SystemPromptDefaults.promptNameForRagScope(" inventory ")).isEqualTo("inventory");
        assertThat(SystemPromptDefaults.promptNameForRagScope("\torder\n")).isEqualTo("order");
        assertThat(SystemPromptDefaults.promptNameForRagScope("   CATALOG   ")).isEqualTo("catalog");
    }

    @Test
    @DisplayName("promptNameForRagScope maps 'shared' to MASTER_PROMPT_NAME")
    void promptNameForRagScope_mapsSharedToMaster() {
        assertThat(SystemPromptDefaults.promptNameForRagScope("shared"))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
        assertThat(SystemPromptDefaults.promptNameForRagScope("SHARED"))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
        assertThat(SystemPromptDefaults.promptNameForRagScope(" shared "))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
    }

    @Test
    @DisplayName("promptNameForRagScope maps null to MASTER_PROMPT_NAME")
    void promptNameForRagScope_mapsNullToMaster() {
        // RagScope.normalize(null) returns "master"
        // promptNameForRagScope then checks if normalized value is "shared"
        // In this case, it's not, so it returns the normalized value "master"
        assertThat(SystemPromptDefaults.promptNameForRagScope(null)).isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
    }

    @Test
    @DisplayName("promptNameForRagScope maps blank string to MASTER_PROMPT_NAME")
    void promptNameForRagScope_mapsBlankToMaster() {
        // RagScope.normalize("") returns "master" (via isEmpty check)
        assertThat(SystemPromptDefaults.promptNameForRagScope("")).isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
        assertThat(SystemPromptDefaults.promptNameForRagScope("   "))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
        assertThat(SystemPromptDefaults.promptNameForRagScope("\t\n"))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
    }

    @Test
    @DisplayName("promptNameForRagScope handles all normalized cases consistently")
    void promptNameForRagScope_handlesAllCasesConsistently() {
        // Verify the complete behavior matrix for normalization + "shared" check
        assertThat(SystemPromptDefaults.promptNameForRagScope("master")).isEqualTo("master");
        assertThat(SystemPromptDefaults.promptNameForRagScope("MASTER")).isEqualTo("master");
        assertThat(SystemPromptDefaults.promptNameForRagScope(" MASTER ")).isEqualTo("master");

        assertThat(SystemPromptDefaults.promptNameForRagScope("shared"))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
        assertThat(SystemPromptDefaults.promptNameForRagScope("SHARED"))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);
        assertThat(SystemPromptDefaults.promptNameForRagScope(" SHARED "))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);

        // Other scopes pass through after normalization
        assertThat(SystemPromptDefaults.promptNameForRagScope("inventory")).isEqualTo("inventory");
        assertThat(SystemPromptDefaults.promptNameForRagScope("INVENTORY")).isEqualTo("inventory");
    }

    @Test
    @DisplayName("promptNameForRagScope distinguishes 'master' from 'shared'")
    void promptNameForRagScope_distinguishesMasterFromShared() {
        // "master" should NOT be mapped to MASTER_PROMPT_NAME by the if check
        // (it's already the master by default, no remapping needed)
        assertThat(SystemPromptDefaults.promptNameForRagScope("master")).isEqualTo("master");

        // "shared" should be explicitly mapped to MASTER_PROMPT_NAME by the if check
        assertThat(SystemPromptDefaults.promptNameForRagScope("shared"))
                .isEqualTo(SystemPromptDefaults.MASTER_PROMPT_NAME);

        // Both should equal the same value (since MASTER_PROMPT_NAME is "master")
        assertThat(SystemPromptDefaults.MASTER_PROMPT_NAME).isEqualTo("master");
    }
}
