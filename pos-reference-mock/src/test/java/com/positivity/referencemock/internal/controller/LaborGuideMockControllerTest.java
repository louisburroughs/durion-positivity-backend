package com.positivity.referencemock.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.referencemock.internal.service.ChaosService;
import com.positivity.referencemock.internal.service.LaborGuideFixtureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * MockMvc tests over the real fixture-backed service, so the wire contract (paths, params,
 * status codes, field names) is pinned exactly as the pos-catalog mockguide adapter consumes it.
 */
class LaborGuideMockControllerTest {

    private static final String MANIFEST_ID = "7f1e6b2a-4c5d-4e8f-9a0b-1c2d3e4f5a6b";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LaborGuideFixtureService service =
                new LaborGuideFixtureService(JsonMapper.builder().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new LaborGuideMockController(service, new ChaosService()))
                .build();
    }

    @Test
    void operationsReturnsArrayOfContractShapedEntries() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/operations")
                        .param("year", "2019")
                        .param("make", "Honda")
                        .param("model", "Civic")
                        .param("search", "brake pad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].providerOperationCode").value("MG-BRAKE-PAD-FRONT"))
                .andExpect(jsonPath("$[0].name").value("Brake Pad Replacement - Front"))
                .andExpect(jsonPath("$[0].category").value("REPAIR"));
    }

    @Test
    void laborTimesReturnsContractShapedBody() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/labor-times")
                        .param("providerOperationCode", "MG-BRAKE-ROTOR-FRONT-PAIR")
                        .param("year", "2019")
                        .param("make", "Honda")
                        .param("model", "Civic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerOperationCode").value("MG-BRAKE-ROTOR-FRONT-PAIR"))
                .andExpect(jsonPath("$.timeType").value("RETAIL_FLAT_RATE"))
                .andExpect(jsonPath("$.includedOperations[0]").value("BRAKE-PAD-FRONT"))
                .andExpect(jsonPath("$.overlapGroup").value("WHEEL-OFF"))
                .andExpect(jsonPath("$.sourceRevision").value("2026-09-01"))
                .andExpect(jsonPath("$.publishedAt").value("2026-09-01"));
    }

    @Test
    void laborTimesMissIsBodilessNotFound() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/labor-times")
                        .param("providerOperationCode", "MG-BRAKE-PAD-FRONT")
                        .param("year", "1988")
                        .param("make", "Yugo")
                        .param("model", "GV"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void manifestIsReturnedEvenWhenSinceRevisionIsCurrent() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/feed/manifest").param("sinceRevision", "2026-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importManifestId").value(MANIFEST_ID))
                .andExpect(jsonPath("$.sourceRevision").value("2026-09-01"))
                .andExpect(jsonPath("$.expectedChunkCount").isNumber())
                .andExpect(jsonPath("$.expectedLineCount").isNumber())
                .andExpect(jsonPath("$.contentChecksum").isString());
    }

    @Test
    void chunkReturnsContractShapedLines() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/feed/chunks/1").param("manifestId", MANIFEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importManifestId").value(MANIFEST_ID))
                .andExpect(jsonPath("$.chunkSequence").value(1))
                .andExpect(jsonPath("$.lines[0].providerOperationCode").value("MG-DIAG-SCAN"))
                .andExpect(jsonPath("$.lines[0].hours").value(1.0))
                .andExpect(jsonPath("$.lines[0].timeType").value("RETAIL_FLAT_RATE"));
    }

    @Test
    void unknownChunkSequenceIsBodilessNotFound() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/feed/chunks/9999").param("manifestId", MANIFEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void mismatchedManifestIdIsBodilessNotFound() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/feed/chunks/1")
                        .param("manifestId", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void failRateOneAlwaysYieldsBodilessServiceUnavailable() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/feed/manifest").param("failRate", "1.0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
        mockMvc.perform(get("/mock/labor-guide/v1/operations").param("failRate", "1.0"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/mock/labor-guide/v1/labor-times")
                        .param("providerOperationCode", "MG-DIAG-SCAN")
                        .param("failRate", "1.0"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/mock/labor-guide/v1/feed/chunks/1")
                        .param("manifestId", MANIFEST_ID)
                        .param("failRate", "1.0"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void failRateZeroNeverFails() throws Exception {
        mockMvc.perform(get("/mock/labor-guide/v1/feed/manifest").param("failRate", "0.0"))
                .andExpect(status().isOk());
    }

    @Test
    void delayMsDelaysTheResponse() throws Exception {
        long start = System.nanoTime();
        mockMvc.perform(get("/mock/labor-guide/v1/feed/manifest").param("delayMs", "200"))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(200);
    }
}
