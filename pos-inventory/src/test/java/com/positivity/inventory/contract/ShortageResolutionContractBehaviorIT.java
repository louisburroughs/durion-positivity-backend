package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.service.ShortageResolutionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Shortage Resolution Contract Behavior — Story #25 (CAP-220)")
class ShortageResolutionContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ShortageResolutionService shortageResolutionService;

        private MockHttpServletRequestBuilder withShortageAuth(MockHttpServletRequestBuilder requestBuilder) {
                return requestBuilder
                                .header("X-User", "contract-test-user")
                                .header("X-Authorities", "inventory:shortage:resolve");
        }

        @Test
        @DisplayName("POST /v1/inventory/shortage/resolve with valid request → 200 OK with response body")
        void resolveShortage_validRequest_returns200() throws Exception {
                UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                ShortageResolutionResultDto mockResponse = ShortageResolutionResultDto.builder()
                                .allocationId(allocationId)
                                .resolution("BACKORDER")
                                .resolvedAt(Instant.parse("2024-01-01T00:00:00Z"))
                                .status("RESOLVED")
                                .build();

                when(shortageResolutionService.resolveShortage(any(ShortageResolveRequest.class)))
                                .thenReturn(mockResponse);

                ShortageResolveRequest request = ShortageResolveRequest.builder()
                                .allocationId(allocationId)
                                .resolution("BACKORDER")
                                .build();

                mockMvc.perform(withShortageAuth(post("/v1/inventory/shortage/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.allocationId").value(allocationId.toString()))
                                .andExpect(jsonPath("$.resolution").value("BACKORDER"))
                                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                                .andExpect(jsonPath("$.status").value("RESOLVED"));
        }

        @Test
        @DisplayName("POST /v1/inventory/shortage/resolve with missing allocationId → 400 Bad Request")
        void resolveShortage_missingAllocationId_returns400() throws Exception {
                String requestBody = """
                                {"resolution":"BACKORDER"}
                                """;

                mockMvc.perform(withShortageAuth(post("/v1/inventory/shortage/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /v1/inventory/shortage/resolve with missing resolution → 400 Bad Request")
        void resolveShortage_missingResolution_returns400() throws Exception {
                UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                String requestBody = String.format("""
                                                                {"allocationId":"%s"}
                                """, allocationId);

                mockMvc.perform(withShortageAuth(post("/v1/inventory/shortage/resolve"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /v1/inventory/shortage/resolve without required authority → 403 Forbidden")
        void resolveShortage_missingRequiredAuthority_returns403() throws Exception {
                ShortageResolveRequest request = ShortageResolveRequest.builder()
                                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .resolution("BACKORDER")
                                .build();

                mockMvc.perform(post("/v1/inventory/shortage/resolve")
                                .header("X-User", "test-user")
                                .header("X-Authorities", "unrelated:authority")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden());
        }
}
