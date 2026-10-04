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
 * #2415: the IDENTIFIER prompt layer keeps internal UUIDs out of chat replies. It is always present,
 * assembled last (after WRITE_GATE, whose verbatim-echo rule it narrows), and only {@code ROLE_ADMIN}
 * gets the explicit-request exception.
 */
class IdentifierDisplayLayerTest {

    private RolePromptResolverImpl resolver;

    @BeforeEach
    void setUp() {
        SystemPromptRepository repository = mock(SystemPromptRepository.class);
        when(repository.findByName(anyString())).thenReturn(Optional.empty());
        resolver = TestSnapshots.resolver(repository, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("a non-admin role gets the default IDENTIFIER layer, never the admin exception")
    void technicianGetsDefaultVariant() {
        AssembledPrompt prompt = resolver.assemble("ROLE_TECHNICIAN", "master", false);

        assertThat(prompt.layers()).contains("IDENTIFIER");
        assertThat(prompt.text())
                .contains(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT)
                .doesNotContain(SystemPromptDefaults.IDENTIFIER_LAYER_ADMIN_EXCEPTION);
    }

    @Test
    @DisplayName("ROLE_ADMIN gets the admin variant: explicit-request exception replaces the refusal bullet")
    void adminGetsExceptionVariant() {
        AssembledPrompt prompt = resolver.assemble("ROLE_ADMIN", "master", false);

        assertThat(prompt.layers()).contains("IDENTIFIER");
        assertThat(prompt.text())
                .contains(SystemPromptDefaults.IDENTIFIER_LAYER_ADMIN_TEXT)
                .contains(SystemPromptDefaults.IDENTIFIER_LAYER_ADMIN_EXCEPTION)
                .doesNotContain(SystemPromptDefaults.IDENTIFIER_LAYER_DEFAULT_REQUEST_RULE);
    }

    @Test
    @DisplayName("the admin exception needs the word UUID; a generic id request gets the business identifier")
    void adminExceptionRequiresExplicitUuidRequest() {
        assertThat(SystemPromptDefaults.IDENTIFIER_LAYER_ADMIN_EXCEPTION)
                .contains("explicitly asks for a UUID by that word")
                .contains("only the UUIDs asked for")
                .contains("A request for an \"id\", \"record id\" or \"system id\" gets the business identifier.")
                .contains("A list, summary or report request still uses business identifiers.");
    }

    @Test
    @DisplayName("ROLE_USER fallback and PLATFORM_ADMIN get the default variant (exact ROLE_ADMIN match only)")
    void fallbackAndOtherAdminsGetDefault() {
        assertThat(resolver.assemble("ROLE_USER", "master", false).text())
                .contains(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT);
        assertThat(SystemPromptDefaults.identifierLayerText("PLATFORM_ADMIN"))
                .isEqualTo(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT);
        assertThat(SystemPromptDefaults.identifierLayerText("ADMIN"))
                .isEqualTo(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT);
        assertThat(SystemPromptDefaults.identifierLayerText(null))
                .isEqualTo(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT);
    }

    @Test
    @DisplayName("IDENTIFIER is the last layer, after GLOSSARY when read-only")
    void lastAfterGlossaryWhenReadOnly() {
        AssembledPrompt prompt = resolver.assemble("ROLE_TECHNICIAN", "master", false);

        assertThat(prompt.layers()).endsWith("GLOSSARY", "IDENTIFIER");
        assertThat(prompt.text().indexOf(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT))
                .isGreaterThan(prompt.text().indexOf(SystemPromptDefaults.GLOSSARY_LAYER_TEXT));
    }

    @Test
    @DisplayName("IDENTIFIER follows WRITE_GATE when write-capable tools are present")
    void lastAfterWriteGateWhenWriteCapable() {
        AssembledPrompt prompt = resolver.assemble("ROLE_SERVICE_ADVISOR", "master", true);

        assertThat(prompt.layers()).endsWith("GLOSSARY", "WRITE_GATE", "IDENTIFIER");
        assertThat(prompt.text().indexOf(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT))
                .isGreaterThan(prompt.text().indexOf(SystemPromptDefaults.WRITE_GATE_LAYER_TEXT));
        assertThat(prompt.text()).endsWith(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT);
    }

    @Test
    @DisplayName("both variants carry the write-preview, userId and precedence bullets")
    void carriesPreviewAndPrecedenceBullets() {
        for (String layer : new String[] {
            SystemPromptDefaults.IDENTIFIER_LAYER_TEXT, SystemPromptDefaults.IDENTIFIER_LAYER_ADMIN_TEXT
        }) {
            assertThat(layer)
                    .startsWith("Identifier display contract:")
                    .contains("Never show internal UUIDs")
                    .contains("UUIDs are for tool arguments only")
                    .contains("In a write preview, show an id-valued argument as the record it names")
                    .contains("The system still executes the exact previewed arguments.")
                    .contains("Never show the authenticated user's userId.")
                    .contains("These rules take precedence over any role persona or domain guidance above them.");
        }
        assertThat(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT)
                .contains("say internal ids are not shown in chat and give the business identifier instead");
    }
}
