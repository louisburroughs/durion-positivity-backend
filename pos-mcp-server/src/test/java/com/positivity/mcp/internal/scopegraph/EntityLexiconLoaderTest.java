package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.mcp.internal.scopegraph.EntityLexicon.EntityDefinition;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.FacadeToolRef;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.Identifier;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.Relation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntityLexiconLoaderTest {

    @Test
    @DisplayName("the fixture lexicon loads into records, every field of spec 3.1")
    void loadsEveryField() {
        EntityLexicon lexicon = ScopeGraphTestFixtures.lexicon();

        assertThat(lexicon.domainScopes()).containsEntry("shop-manager", "shopmanager");
        assertThat(lexicon.unscopedTools()).containsExactly("DateWindowFacadeTool");
        assertThat(lexicon.entities()).extracting(EntityDefinition::key).containsExactly("workorder", "estimate");

        EntityDefinition workorder = lexicon.entities().getFirst();
        assertThat(workorder.domain()).isEqualTo("workorder");
        assertThat(workorder.terms())
                .containsEntry("en", List.of("work order", "workorder"))
                .containsEntry("fr", List.of("bon de travail"))
                .containsEntry("es", List.of("orden de trabajo"));
        assertThat(workorder.identifiers())
                .containsExactly(new Identifier("workorder-number", "\\bWO-\\d{4}-\\d{4,}\\b"));
        assertThat(workorder.relatesTo()).containsExactly(new Relation("estimate", "promoted_from"));
        assertThat(workorder.schemas()).containsExactly("workorder:WorkorderResponse");
        assertThat(workorder.schemaPatterns()).containsExactly("workorder:Workorder(Create|Part).*");
        assertThat(workorder.facadeTools()).containsExactly(new FacadeToolRef("WorkorderFacadeTool", Access.READS));
        assertThat(workorder.screens()).containsExactly("workorders.list");

        EntityDefinition estimate = lexicon.entities().get(1);
        assertThat(estimate.identifiers()).isEmpty();
        assertThat(estimate.screens()).isEmpty();
        assertThat(estimate.facadeTools()).containsExactly(new FacadeToolRef("WorkorderFacadeTool", Access.WRITES));
    }

    @Test
    @DisplayName("the lexicon shipped with the module loads")
    void shippedLexiconLoads() {
        EntityLexicon lexicon = EntityLexiconLoader.loadDefault();

        assertThat(lexicon.entities()).isNotEmpty();
        assertThat(lexicon.entities()).extracting(EntityDefinition::key).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName(
            "ADR-0068: the domains block loads as scope -> sentence, and the shipped lexicon carries one per scope")
    void domainsBlockLoads() {
        EntityLexicon lexicon =
                load("domains:\n  accounting: \"General ledger.\"\n  master: \"No single area.\"\nentities: []\n");

        assertThat(lexicon.domains())
                .containsOnly(
                        java.util.Map.entry("accounting", "General ledger."),
                        java.util.Map.entry("master", "No single area."));
        assertThat(load("entities: []\n").domains()).isEmpty();
        assertThatThrownBy(() -> load("domains: [accounting]\nentities: []\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("domains");
        assertThatThrownBy(() -> load("domains:\n  accounting: \"\"\nentities: []\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("domains");
        assertThat(EntityLexiconLoader.loadDefault().domains()).containsKeys("accounting", "workorder", "master");
    }

    @Test
    @DisplayName("text that is not YAML is rejected")
    void invalidYaml() {
        assertThatThrownBy(() -> load("entities: [unterminated")).isInstanceOf(EntityLexiconException.class);
        assertThatThrownBy(() -> load("- just\n- a list\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    @DisplayName("an entity without a key is reported by its position")
    void missingKey() {
        assertThatThrownBy(() -> load("entities:\n  - domain: workorder\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entities[0]")
                .hasMessageContaining("key");
    }

    @Test
    @DisplayName("a repeated YAML key is rejected instead of silently keeping the last one")
    void duplicateYamlKey() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: workorder
                    domain: workorder
                    schema_patterns: ["workorder:Workorder.*"]
                    schema_patterns: ["workorder:Estimate.*"]
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("schema_patterns");
        assertThatThrownBy(() -> load("entities: []\nentities: []\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entities");
    }

    @Test
    @DisplayName("a duplicate entity key is rejected and named")
    void duplicateKey() {
        assertThatThrownBy(() -> load("""
                entities:
                  - {key: workorder, domain: workorder}
                  - {key: workorder, domain: order}
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("'workorder'");
    }

    @Test
    @DisplayName("a missing domain names the entity")
    void missingDomain() {
        assertThatThrownBy(() -> load("entities:\n  - key: invoice\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'invoice'")
                .hasMessageContaining("domain");
    }

    @Test
    @DisplayName("a misspelt field is rejected instead of being silently ignored")
    void unknownField() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: invoice
                    domain: invoice
                    facade_tool: [InvoiceFacadeTool]
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'invoice'")
                .hasMessageContaining("facade_tool");
        assertThatThrownBy(() -> load("entity: []\n"))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("unknown field 'entity'");
    }

    @Test
    @DisplayName("an access other than reads or writes names the entity and the tool")
    void invalidAccess() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: invoice
                    domain: invoice
                    facade_tools:
                      - {tool: InvoiceFacadeTool, access: updates}
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'invoice'")
                .hasMessageContaining("InvoiceFacadeTool")
                .hasMessageContaining("updates");
    }

    @Test
    @DisplayName("a regex that does not compile names the entity, for identifiers and schema patterns")
    void invalidRegex() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    identifiers:
                      - {key: vin, pattern: '[A-Z'}
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'vehicle'")
                .hasMessageContaining("identifiers");
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    schema_patterns: ['vehicle:(Vehicle']
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'vehicle'")
                .hasMessageContaining("schema_patterns");
    }

    @Test
    @DisplayName("a schema reference must be written domain:SchemaName")
    void unqualifiedSchema() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    schemas: [VehicleResponse]
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'vehicle'")
                .hasMessageContaining("domain:SchemaName");
    }

    @Test
    @DisplayName("wrongly shaped terms, lists and items name the entity")
    void wrongShapes() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    terms: [car, truck]
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'vehicle'");
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    screens: vehicles.list
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'vehicle'")
                .hasMessageContaining("screens");
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    relates_to: [customer]
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'vehicle'")
                .hasMessageContaining("relates_to");
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    terms:
                      en: ['']
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("one identifier key cannot carry two patterns")
    void conflictingIdentifierPatterns() {
        assertThatThrownBy(() -> load("""
                entities:
                  - key: vehicle
                    domain: vehicle
                    identifiers:
                      - {key: vin, pattern: '[A-Z0-9]{17}'}
                  - key: trailer
                    domain: vehicle
                    identifiers:
                      - {key: vin, pattern: '[A-Z0-9]{11}'}
                """))
                .isInstanceOf(EntityLexiconException.class)
                .hasMessageContaining("entity 'trailer'")
                .hasMessageContaining("'vin'")
                .hasMessageContaining("'vehicle'");
    }

    @Test
    @DisplayName("an empty lexicon is valid: no entities, no map, no unscoped tools")
    void emptyLexicon() {
        EntityLexicon lexicon = load("entities: []\n");

        assertThat(lexicon.entities()).isEmpty();
        assertThat(lexicon.domainScopes()).isEmpty();
        assertThat(lexicon.unscopedTools()).isEmpty();
    }

    private static EntityLexicon load(String yaml) {
        return EntityLexiconLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
