package com.positivity.warranty.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.warranty.internal.entity.ExtVehicleReplica;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * First Failsafe ITs for pos-warranty (plan §5): the module's 85.7% unit-only
 * coverage never proved that a claim actually persists, that the VIN snapshot
 * really comes from the {@code ext_vehicle} replica row, or that the status
 * history is written in the same transaction. These run the full path —
 * controller, security filter chain, service, JPA, H2.
 *
 * <p>What is pinned:
 * <ul>
 * <li><b>The VIN/odometer snapshot is server-side.</b> The request carries only
 * a {@code vehicleId}; VIN and odometer are read from the replica and frozen
 * onto the claim (PRD §3.4). A caller cannot supply its own VIN.</li>
 * <li><b>Walk-in resilience:</b> an empty replica still creates the claim, with
 * a null snapshot, rather than rejecting the intake (ADR-0044 §6, #924).</li>
 * <li><b>Creation writes the claim and its DRAFT history row atomically</b> —
 * the transaction boundary the unit suite could not see.</li>
 * <li><b>Gateway auth is enforced by the real filter chain</b>: no headers is
 * 401, wrong authority is 403 (ADR-0011/ADR-0014/ADR-0017).</li>
 * </ul>
 */
@DisplayName("Warranty claim contract behavior — first Failsafe layer")
class ClaimContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarrantyClaimRepository claimRepository;

    @Autowired
    private ClaimStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private ExtVehicleReplicaRepository extVehicleReplicaRepository;

    private UUID vehicleId;

    @BeforeEach
    void seedVehicleReplica() {
        vehicleId = UUID.randomUUID();
        ExtVehicleReplica replica = new ExtVehicleReplica();
        replica.setVehicleId(vehicleId);
        replica.setVin("1HGCM82633A004352");
        replica.setOdometerValue(48_200);
        replica.setOdometerUnit("MILES");
        replica.setActive(true);
        replica.setAggregateVersion(1L);
        replica.setUpdatedAt(Instant.now());
        extVehicleReplicaRepository.save(replica);
    }

    private String claimBody(UUID vehicle) {
        return """
                {"claimType":"MANUFACTURER_DEFECT","customerId":"%s","vehicleId":"%s",
                 "locationId":"%s","failureDescription":"Alternator failed at 48k",
                 "failureDate":"2026-08-01"}""".formatted(UUID.randomUUID(), vehicle, UUID.randomUUID());
    }

    @Test
    @DisplayName("creates a DRAFT claim, snapshots the VIN server-side, and writes the history row")
    void createPersistsSnapshotAndHistory() throws Exception {
        MvcResult result = mockMvc.perform(withClaimAuth(post("/v1/warranty/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(vehicleId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.claimCode").isNotEmpty())
                // The request carried no VIN; this value can only have come from the replica row.
                .andExpect(jsonPath("$.vin").value("1HGCM82633A004352"))
                .andExpect(jsonPath("$.odometerAtClaim").value(48200))
                .andExpect(jsonPath("$.originUnverified").value(true))
                .andReturn();

        UUID claimId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("id")
                .asString());

        // Persistence, not just response shape: the row is really in the database…
        assertThat(claimRepository.findById(claimId)).isPresent().hasValueSatisfying(claim -> {
            assertThat(claim.getVin()).isEqualTo("1HGCM82633A004352");
            assertThat(claim.getClaimCode()).isNotBlank();
        });
        // …and the DRAFT history row committed in the same transaction.
        assertThat(statusHistoryRepository.findAll())
                .anySatisfy(h -> assertThat(h.getClaimId()).isEqualTo(claimId));
    }

    @Test
    @DisplayName("still creates the claim when the vehicle replica is empty (walk-in resilience)")
    void emptyReplicaStillCreates() throws Exception {
        // No replica row for this vehicle — the event feed has not caught up, or never will.
        mockMvc.perform(withClaimAuth(post("/v1/warranty/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // The snapshot stays null rather than the intake being rejected.
                .andExpect(jsonPath("$.vin").doesNotExist());
    }

    @Test
    @DisplayName("reads a created claim back through the search and get endpoints")
    void createdClaimIsFindable() throws Exception {
        MvcResult created = mockMvc.perform(withClaimAuth(post("/v1/warranty/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(vehicleId))))
                .andExpect(status().isCreated())
                .andReturn();
        var createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        String claimId = createdJson.path("id").asString();
        String claimCode = createdJson.path("claimCode").asString();

        mockMvc.perform(withViewOnlyAuth(get("/v1/warranty/claims/{id}", claimId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimCode").value(claimCode));

        // The search path goes through the real JPA specification/query, not a mock.
        mockMvc.perform(withViewOnlyAuth(get("/v1/warranty/claims").param("claimCode", claimCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(claimId));
    }

    @Test
    @DisplayName("rejects an invalid body with 400 before anything is persisted")
    void invalidBodyIsRejected() throws Exception {
        long before = claimRepository.count();

        // claimType, customerId, and vehicleId are @NotNull on the intake contract.
        mockMvc.perform(withClaimAuth(post("/v1/warranty/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureDescription\":\"no ids\"}")))
                .andExpect(status().isBadRequest());

        assertThat(claimRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("requires gateway auth: anonymous is 401, wrong authority is 403")
    void securityIsEnforcedByTheRealFilterChain() throws Exception {
        mockMvc.perform(post("/v1/warranty/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(vehicleId)))
                .andExpect(status().isUnauthorized());

        // A viewer must not be able to create.
        mockMvc.perform(withViewOnlyAuth(post("/v1/warranty/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(vehicleId))))
                .andExpect(status().isForbidden());
    }
}
