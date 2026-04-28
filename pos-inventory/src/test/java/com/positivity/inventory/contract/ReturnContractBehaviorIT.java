package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.returns.ReturnLineDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.service.ReturnService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Return Contract Behavior — Story #177")
class ReturnContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ReturnService returnService;

        @Test
        @DisplayName("POST /v1/inventory/returns/submit-to-stock with valid body + auth -> 202 Accepted")
        void RC1_submitToStock_validBodyAndAuth_returns202WithServicePayload() throws Exception {

                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID expectedReturnId = UUID.fromString("00000000-0000-0000-0000-000000000042");

                ReturnSubmissionResultDto expectedResponse = ReturnSubmissionResultDto.builder()
                                .returnId(expectedReturnId)
                                .workorderId(workorderId)
                                .processedLines(1)
                                .status("SUBMITTED")
                                .processedAt(Instant.now(TEST_CLOCK))
                                .build();
                when(returnService.submitToStock(any(ReturnSubmitRequest.class))).thenReturn(expectedResponse);

                ReturnSubmitRequest requestBody = ReturnSubmitRequest.builder()
                                .workorderId(workorderId)
                                .lines(List.of(ReturnLineDto.builder()
                                                .itemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .quantity(2)
                                                .reasonCode("DAMAGED")
                                                .locationId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
                                                .build()))
                                .build();

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/returns/submit-to-stock"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.returnId").value(expectedReturnId.toString()))
                                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                                .andExpect(jsonPath("$.processedLines").value(1))
                                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                                .andExpect(jsonPath("$.processedAt").isNotEmpty());
        }

        @Test
        @DisplayName("POST /v1/inventory/returns/submit-to-stock without required authority -> 403 Forbidden")
        void RC2_submitToStock_missingRequiredAuthority_returns403() throws Exception {
                ReturnSubmitRequest requestBody = ReturnSubmitRequest.builder()
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .lines(List.of(ReturnLineDto.builder()
                                                .itemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .quantity(1)
                                                .reasonCode("EXCESS")
                                                .locationId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
                                                .build()))
                                .build();

                mockMvc.perform(post("/v1/inventory/returns/submit-to-stock")
                                .header("X-User", "test-user")
                                .header("X-Authorities", "unrelated:authority")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /v1/inventory/returns/submit-to-stock with invalid line fields -> 400 Bad Request")
        void RC3_submitToStock_invalidRequest_returns400() throws Exception {
                ReturnSubmitRequest requestBody = ReturnSubmitRequest.builder()
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .lines(List.of(ReturnLineDto.builder()
                                                .itemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                .quantity(1)
                                                .reasonCode("   ")
                                                .locationId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
                                                .build()))
                                .build();

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/returns/submit-to-stock"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /v1/inventory/returns/submit-to-stock with quantity exceeded -> 422 Unprocessable Entity")
        void RC4_submitToStock_quantityExceeded_returns422() throws Exception {
                UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(returnService.submitToStock(any(ReturnSubmitRequest.class)))
                                .thenThrow(new ReturnQuantityExceededException(skuId, 99, 2));

                ReturnSubmitRequest requestBody = ReturnSubmitRequest.builder()
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .lines(List.of(ReturnLineDto.builder()
                                                .itemId(skuId)
                                                .quantity(99)
                                                .reasonCode("DAMAGED")
                                                .locationId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
                                                .build()))
                                .build();

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/returns/submit-to-stock"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().is(422));
        }
}
