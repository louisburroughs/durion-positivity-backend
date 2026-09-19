package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"eureka.client.enabled=false", "pos.security.permission-registration.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("openApiSpec_mcpEndpoints_haveRequiredSummaryAndDescription")
    @SuppressWarnings("unchecked")
    void openApiSpec_mcpEndpoints_haveRequiredSummaryAndDescription() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

        Map<String, Object> openApiSpec =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationSummaryAndDescription(paths, "/v1/prompts", "get");
        assertOperationSummaryAndDescription(paths, "/v1/prompts/{id}", "get");
        assertOperationSummaryAndDescription(paths, "/v1/prompts", "post");
        assertOperationSummaryAndDescription(paths, "/v1/prompts/{id}", "put");
        assertOperationSummaryAndDescription(paths, "/v1/prompts/{id}", "delete");
        assertOperationDescription(paths, "/v1/nlt/requests", "post");
        assertOperationDescription(paths, "/v1/mcp/chat", "post");
        assertOperationDescription(paths, "/v1/mcp/chat/stream", "post");
        assertOperationSummaryAndDescription(paths, "/v1/tools/{toolName}/permissions", "get");
        assertOperationSummaryAndDescription(paths, "/v1/tools/{toolName}/permissions", "post");
        assertOperationSummaryAndDescription(paths, "/v1/tools/{toolName}/permissions", "delete");
        // #2075
        assertOperationSummaryAndDescription(paths, "/v1/mcp/conversations/{id}/messages/{messageId}/feedback", "post");
        assertOperationSummaryAndDescription(
                paths, "/v1/mcp/conversations/{id}/messages/{messageId}/feedback", "delete");
    }

    @Test
    @DisplayName("openApiSpec_chatBlock_isInheritanceNotSelfReferencingUnion")
    @SuppressWarnings("unchecked")
    void openApiSpec_chatBlock_isInheritanceNotSelfReferencingUnion() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

        Map<String, Object> openApiSpec =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> schemas =
                (Map<String, Object>) ((Map<String, Object>) openApiSpec.get("components")).get("schemas");

        Map<String, Object> chatBlock = (Map<String, Object>) schemas.get("ChatBlock");
        assertThat(chatBlock).as("ChatBlock schema should exist").isNotNull();

        // springdoc emits every variant as `allOf: [$ref ChatBlock, {...}]` because the records
        // implement the interface. Declaring `oneOf` here as well made the parent a union over
        // schemas that each referenced the parent back, and openapi-generator turned that circle
        // into `interface ChatTextBlock extends ChatTextBlock` — a TypeScript SDK that does not
        // compile (TS2310/TS2312/TS2698). Keep the two apart: inheritance here, never both.
        assertThat(chatBlock)
                .as("ChatBlock must not be a oneOf union over variants that extend it — see @Schema(subTypes)")
                .doesNotContainKey("oneOf");

        Map<String, Object> discriminator = (Map<String, Object>) chatBlock.get("discriminator");
        assertThat(discriminator).as("ChatBlock should keep its discriminator").isNotNull();
        assertThat(discriminator.get("propertyName")).isEqualTo("kind");

        Map<String, String> mapping = (Map<String, String>) discriminator.get("mapping");
        assertThat(mapping)
                .as("every ChatBlock variant should stay reachable through the discriminator mapping")
                .containsOnlyKeys("markdown", "table", "chart", "code", "text", "image", "file", "error");

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String variantName = entry.getValue().substring(entry.getValue().lastIndexOf('/') + 1);
            Map<String, Object> variant = (Map<String, Object>) schemas.get(variantName);
            assertThat(variant)
                    .as("variant %s mapped from kind=%s should exist", variantName, entry.getKey())
                    .isNotNull();
            assertThat((List<Object>) variant.get("allOf"))
                    .as("%s should extend ChatBlock", variantName)
                    .isNotNull()
                    .contains(Map.of("$ref", "#/components/schemas/ChatBlock"));
            assertThat(variant)
                    .as("%s should not itself be a union", variantName)
                    .doesNotContainKey("oneOf");
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationSummaryAndDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        assertThat(operation.get("summary"))
                .as("%s %s should have a non-empty summary", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
