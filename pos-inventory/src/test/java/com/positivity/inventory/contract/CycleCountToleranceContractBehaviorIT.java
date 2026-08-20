package com.positivity.inventory.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.CycleCountTolerance;
import com.positivity.inventory.internal.repository.CycleCountToleranceRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior integration tests for cycle-count tolerance admin CRUD (ADR-0055 stage 4,
 * #1416).
 */
@DisplayName("Cycle Count Tolerance Contract Behavior")
class CycleCountToleranceContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CycleCountToleranceRepository toleranceRepository;

    @BeforeEach
    void setUp() {
        toleranceRepository.deleteAll();
    }

    @Test
    @DisplayName("creating a tolerance returns 201 with the persisted scope and bounds")
    void create_validRequest_returns201() throws Exception {
        String body = """
                {"productId":"01960003-0000-7000-8000-000000000001",
                 "storageLocation":"TANK-04",
                 "absoluteTolerance":50,
                 "percentageTolerance":1.5,
                 "createdBy":"tester"}
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountTolerances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageLocation").value("TANK-04"))
                .andExpect(jsonPath("$.absoluteTolerance").value(50))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("creating a duplicate active scope returns 400")
    void create_duplicateScope_returns400() throws Exception {
        toleranceRepository.save(CycleCountTolerance.builder()
                .storageLocation("TANK-05")
                .absoluteTolerance(new BigDecimal("10"))
                .active(true)
                .createdBy("tester")
                .build());

        String body = """
                {"storageLocation":"TANK-05",
                 "absoluteTolerance":20,
                 "createdBy":"tester"}
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountTolerances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("get returns 200 for an existing tolerance and 404 for an unknown id")
    void get_existingAndUnknown() throws Exception {
        CycleCountTolerance saved = toleranceRepository.save(CycleCountTolerance.builder()
                .storageLocation("TANK-06")
                .absoluteTolerance(new BigDecimal("25"))
                .active(true)
                .createdBy("tester")
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCountTolerances/{id}", saved.getToleranceId())))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.toleranceId").value(saved.getToleranceId().toString()));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCountTolerances/{id}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("list returns every configured tolerance")
    void list_returnsAllConfigured() throws Exception {
        toleranceRepository.save(CycleCountTolerance.builder()
                .storageLocation("TANK-07")
                .absoluteTolerance(new BigDecimal("5"))
                .active(true)
                .createdBy("tester")
                .build());
        toleranceRepository.save(CycleCountTolerance.builder()
                .storageLocation("TANK-08")
                .percentageTolerance(new BigDecimal("2"))
                .active(true)
                .createdBy("tester")
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCountTolerances")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("update replaces bounds and active state; unknown id returns 404")
    void update_replacesBounds() throws Exception {
        CycleCountTolerance saved = toleranceRepository.save(CycleCountTolerance.builder()
                .storageLocation("TANK-09")
                .absoluteTolerance(new BigDecimal("10"))
                .active(true)
                .createdBy("tester")
                .build());

        String body = """
                {"absoluteTolerance":99,"active":true}
                """;

        mockMvc.perform(withGatewayAuth(put("/v1/inventory/cycleCountTolerances/{id}", saved.getToleranceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absoluteTolerance").value(99));

        mockMvc.perform(withGatewayAuth(put("/v1/inventory/cycleCountTolerances/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("delete removes an existing tolerance; unknown id returns 404")
    void delete_removesExistingAndRejectsUnknown() throws Exception {
        CycleCountTolerance saved = toleranceRepository.save(CycleCountTolerance.builder()
                .storageLocation("TANK-10")
                .absoluteTolerance(new BigDecimal("15"))
                .active(true)
                .createdBy("tester")
                .build());

        mockMvc.perform(withGatewayAuth(delete("/v1/inventory/cycleCountTolerances/{id}", saved.getToleranceId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCountTolerances/{id}", saved.getToleranceId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(withGatewayAuth(delete("/v1/inventory/cycleCountTolerances/{id}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
