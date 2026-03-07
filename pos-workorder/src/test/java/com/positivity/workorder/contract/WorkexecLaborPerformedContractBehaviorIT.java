package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;

@DisplayName("CAP-121 Workexec Labor Performed Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ ContractTestConfiguration.class, TestSecurityConfig.class })
class WorkexecLaborPerformedContractBehaviorIT extends AbstractWorkexecContractBehaviorIT {

        @Test
        @DisplayName("AC1: POST labor-performed returns 201 and creates labor record")
        void postLaborPerformedReturnsCreatedAndPersistsRecord() throws Exception {
                Workorder workorder = seedWorkorderInProgress();
                UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                String timeEntryId = "te-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> payload = buildLaborPerformedPayload(workorder.getId(), technicianId, timeEntryId);

                mockMvc.perform(post("/v1/workexec/labor-performed")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", timeEntryId)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.laborPerformedId").isNotEmpty())
                                .andExpect(jsonPath("$.workorderId").value(workorder.getId().toString()))
                                .andExpect(jsonPath("$.technicianId").value(technicianId.toString()))
                                .andExpect(jsonPath("$.quantity").value(1.5))
                                .andExpect(jsonPath("$.unit").value("HOURS"))
                                .andExpect(jsonPath("$.sourceSystem").value("people"))
                                .andExpect(jsonPath("$.sourceReferenceId").value(timeEntryId));

                assertThat(laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorder.getId())).hasSize(1)
                                .extracting(WorkorderLaborEntry::getNotes)
                                .singleElement()
                                .asString()
                                .contains("sourceSystem=people")
                                .contains("sourceReferenceId=" + timeEntryId);
        }

        @Test
        @DisplayName("AC2: replay same Idempotency-Key returns 200 with replay header and no duplicate")
        void replayWithSameIdempotencyKeyReturnsOkAndNoDuplicate() throws Exception {
                Workorder workorder = seedWorkorderInProgress();
                UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                String timeEntryId = "te-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> payload = buildLaborPerformedPayload(workorder.getId(), technicianId, timeEntryId);

                String firstResponseBody = mockMvc.perform(post("/v1/workexec/labor-performed")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", timeEntryId)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                String firstLaborPerformedId = objectMapper.readTree(firstResponseBody)
                                .path("laborPerformedId")
                                .asText();

                mockMvc.perform(post("/v1/workexec/labor-performed")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", timeEntryId)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Idempotency-Replayed", "true"))
                                .andExpect(jsonPath("$.laborPerformedId").value(firstLaborPerformedId))
                                .andExpect(jsonPath("$.sourceSystem").value("people"))
                                .andExpect(jsonPath("$.sourceReferenceId").value(timeEntryId));

                assertThat(laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorder.getId())).hasSize(1);
        }

        @Test
        @DisplayName("AC3: missing Idempotency-Key returns 400")
        void missingIdempotencyKeyReturnsBadRequest() throws Exception {
                Workorder workorder = seedWorkorderInProgress();
                UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> payload = buildLaborPerformedPayload(workorder.getId(), technicianId,
                                "te-" + UUID.fromString("00000000-0000-0000-0000-000000000001"));

                mockMvc.perform(post("/v1/workexec/labor-performed")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isBadRequest());

                assertThat(laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorder.getId())).isEmpty();
        }

        private Map<String, Object> buildLaborPerformedPayload(UUID workorderId, UUID technicianId,
                        String timeEntryId) {
                return WorkexecContractPayloads.laborPerformedPayload(
                                workorderId,
                                technicianId,
                                BigDecimal.valueOf(1.5),
                                timeEntryId);
        }
}
