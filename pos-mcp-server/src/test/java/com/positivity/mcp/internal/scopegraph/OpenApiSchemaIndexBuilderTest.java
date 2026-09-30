package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.scopegraph.OpenApiSchemaIndex.DomainIndex;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec §2.2, over the fixture spec parsed the way the runtime capture parses it: OpenAPI 3.1, every
 * {@code $ref} left in place.
 */
class OpenApiSchemaIndexBuilderTest {

    @Test
    @DisplayName("a plain $ref response and request body are indexed under the persisted tool name")
    void indexesRefRequestAndResponse() {
        OpenApiSchemaIndex index = ScopeGraphTestFixtures.schemaIndex();

        assertThat(index.operationSchemas("workorder_getworkorder")).containsExactly("workorder:WorkorderResponse");
        assertThat(index.operationSchemas("workorder_createworkorder"))
                .containsExactly("workorder:WorkorderCreateRequest", "workorder:WorkorderResponse");
    }

    @Test
    @DisplayName("an array response is indexed by its item schema")
    void followsArrayItems() {
        assertThat(ScopeGraphTestFixtures.schemaIndex().operationSchemas("workorder_listworkorderparts"))
                .containsExactly("workorder:WorkorderPartResponse");
    }

    @Test
    @DisplayName("a page envelope is unwrapped to its content element")
    void unwrapsPageEnvelope() {
        assertThat(ScopeGraphTestFixtures.schemaIndex().operationSchemas("workorder_listworkorders"))
                .containsExactly("workorder:PageWorkorderResponse", "workorder:WorkorderResponse");
    }

    @Test
    @DisplayName("an inline oneOf contributes each member, one level deep")
    void followsOneOfOneLevel() {
        assertThat(ScopeGraphTestFixtures.schemaIndex().operationSchemas("workorder_getestimate"))
                .containsExactly("workorder:EstimateResponse", "workorder:EstimateSummaryResponse");
    }

    @Test
    @DisplayName("nested DTOs are not followed: an operation indexes what it exchanges, not what that contains")
    void doesNotFollowNestedProperties() {
        assertThat(ScopeGraphTestFixtures.schemaIndex().operationSchemas("workorder_getworkorder"))
                .doesNotContain("workorder:WorkorderPartResponse", "workorder:PaymentState");
    }

    @Test
    @DisplayName("an operation with no body is indexed with no schema, and an unknown tool with none")
    void bodylessAndUnknownOperations() {
        OpenApiSchemaIndex index = ScopeGraphTestFixtures.schemaIndex();

        assertThat(index.operationSchemas("workorder_cancelworkorder")).isEmpty();
        assertThat(index.operationSchemas("workorder_nosuchoperation")).isEmpty();
    }

    @Test
    @DisplayName("status and state enums are indexed, inline or through a $ref; other enums are not")
    void indexesStatusAndStateEnums() {
        OpenApiSchemaIndex index = ScopeGraphTestFixtures.schemaIndex();

        assertThat(index.enums("workorder:WorkorderResponse"))
                .containsOnlyKeys("status", "paymentState")
                .containsEntry("status", List.of("DRAFT", "APPROVED", "IN_PROGRESS", "COMPLETED"))
                .containsEntry("paymentState", List.of("UNPAID", "PAID"));
        assertThat(index.enums("workorder:EstimateSummaryResponse")).isEmpty();
        assertThat(index.enums("other:WorkorderResponse")).isEmpty();
    }

    @Test
    @DisplayName("every component schema resolves as domain:SchemaName, and only under its own domain")
    void schemaNamesAreDomainQualified() {
        OpenApiSchemaIndex index = ScopeGraphTestFixtures.schemaIndex();

        assertThat(index.domains()).containsExactly("workorder");
        assertThat(index.hasSchema("workorder:TechnicianResponse")).isTrue();
        assertThat(index.hasSchema("customer:TechnicianResponse")).isFalse();
        assertThat(index.hasSchema("TechnicianResponse")).isFalse();
        assertThat(index.schemaNames()).contains("workorder:EstimateBase", "workorder:PaymentState");
    }

    @Test
    @DisplayName("a composed (allOf) schema contributes itself and the members it extends, one level deep")
    void followsAllOfOneLevel() {
        assertThat(ScopeGraphTestFixtures.schemaIndex().operationSchemas("workorder_updateestimate"))
                .containsExactly(
                        "workorder:EstimateBase", "workorder:EstimateResponse", "workorder:EstimateUpdateRequest");
    }

    @Test
    @DisplayName("a spec parsed with resolveFully has lost its schema names: nothing is indexed, nothing is wrong")
    void fullyResolvedSpecNamesNothing() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        OpenAPI resolved = new OpenAPIV3Parser()
                .readContents(ScopeGraphTestFixtures.read("scope-graph/service-spec.yaml"), null, options)
                .getOpenAPI();

        DomainIndex index =
                OpenApiSchemaIndexBuilder.build(resolved, "workorder", (path, operation) -> operation.getOperationId());

        // This is why the capture re-reads the raw text instead of using discovery's parsed model.
        assertThat(index.operationSchemas().get("getWorkorder")).isEmpty();
        assertThat(index.schemaNames()).contains("WorkorderResponse");
    }

    @Test
    @DisplayName("text that is not an OpenAPI document parses to null")
    void parseUnresolvedRejectsGarbage() {
        assertThat(OpenApiSchemaIndexBuilder.parseUnresolved("<html>502 Bad Gateway</html>"))
                .isNull();
    }

    @Test
    @DisplayName("a merged aggregate is split by the domain of each path")
    void aggregateIsSplitByPathDomain() {
        OpenAPI service = ScopeGraphTestFixtures.spec();
        OpenAPI aggregate = new OpenAPI();
        aggregate.setComponents(service.getComponents());
        Paths paths = new Paths();
        paths.addPathItem("/workorder/v1/workorders/{id}", service.getPaths().get("/v1/workorders/{id}"));
        paths.addPathItem("/people/v1/technicians", service.getPaths().get("/v1/technicians"));
        aggregate.setPaths(paths);

        List<DomainIndex> indexes = OpenApiSchemaIndexBuilder.buildByPathDomain(
                aggregate, OpenApiToolMapper::extractDomain, OpenApiToolMapper::discoveredToolName);
        OpenApiSchemaIndex index = new OpenApiSchemaIndex(indexes);

        assertThat(index.domains()).containsExactly("people", "workorder");
        assertThat(index.operationSchemas("workorder_getworkorder")).containsExactly("workorder:WorkorderResponse");
        assertThat(index.operationSchemas("people_listtechnicians")).containsExactly("people:TechnicianResponse");
    }

    @Test
    @DisplayName("a spec without paths still contributes its schema names")
    void specWithoutPaths() {
        OpenAPI spec = ScopeGraphTestFixtures.spec();
        spec.setPaths(null);

        DomainIndex index = OpenApiSchemaIndexBuilder.build(spec, "workorder", (path, operation) -> "unused");

        assertThat(index.operationSchemas()).isEmpty();
        assertThat(index.schemaNames()).contains("WorkorderResponse");
    }
}
