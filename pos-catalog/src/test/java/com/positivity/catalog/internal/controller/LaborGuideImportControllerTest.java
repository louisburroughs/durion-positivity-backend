package com.positivity.catalog.internal.controller;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.LaborGuideImportSummaryDto;
import com.positivity.catalog.internal.dto.LaborGuideUnmappedOperationDto;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.service.LaborGuideIngestService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LaborGuideImportController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("/v1/catalog/labor-guide-imports (#1569)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class LaborGuideImportControllerTest {

    private static final String BASE = "/v1/catalog/labor-guide-imports";
    private static final UUID MANIFEST_ID = UUID.fromString("7f1e6b2a-4c5d-4e8f-9a0b-1c2d3e4f5a6b");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    LaborGuideIngestService ingestService;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    private static LaborGuideImportSummaryDto summary(String status) {
        LaborGuideImportSummaryDto dto = new LaborGuideImportSummaryDto();
        dto.setImportManifestId(MANIFEST_ID);
        dto.setSourceCode("MOCKGUIDE");
        dto.setSourceRevision("2026-09-01");
        dto.setStatus(status);
        dto.setChunksApplied(1);
        dto.setExpectedChunkCount(1);
        dto.setLinesApplied(1);
        dto.setExpectedLineCount(1);
        dto.setStandardsWritten(1);
        return dto;
    }

    @Test
    @DisplayName("POST ?sourceCode= runs the import and returns its counted outcome")
    void runImportReturnsSummary() throws Exception {
        when(ingestService.runImport("MOCKGUIDE")).thenReturn(summary("COMPLETE"));

        mockMvc.perform(post(BASE).param("sourceCode", "MOCKGUIDE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importManifestId").value(MANIFEST_ID.toString()))
                .andExpect(jsonPath("$.sourceCode").value("MOCKGUIDE"))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.standardsWritten").value(1));
    }

    @Test
    @DisplayName("POST for an unconfigured source is 404")
    void unknownSourceIs404() throws Exception {
        when(ingestService.runImport("NOPE"))
                .thenThrow(new CatalogNotFoundException("No labor-guide provider configured for source NOPE"));

        mockMvc.perform(post(BASE).param("sourceCode", "NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("POST for a QUERY_ONLY source is 409 BUSINESS_RULE_VIOLATION")
    void queryOnlySourceIs409() throws Exception {
        when(ingestService.runImport("MOCKGUIDE_LIVE"))
                .thenThrow(new CatalogBusinessRuleException(
                        "Source MOCKGUIDE_LIVE is licensed QUERY_ONLY; its times are consulted live"
                                + " and may not be imported"));

        mockMvc.perform(post(BASE).param("sourceCode", "MOCKGUIDE_LIVE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @DisplayName("POST without sourceCode is 400 — the parameter is the whole request")
    void missingSourceCodeIs400() throws Exception {
        mockMvc.perform(post(BASE)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /incomplete lists imports still applying or closed incomplete")
    void listIncomplete() throws Exception {
        when(ingestService.listIncompleteImports()).thenReturn(List.of(summary("APPLYING")));

        mockMvc.perform(get(BASE + "/incomplete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPLYING"))
                .andExpect(jsonPath("$[0].importManifestId").value(MANIFEST_ID.toString()));
    }

    @Test
    @DisplayName("GET /unmapped lists the curation queue")
    void listUnmapped() throws Exception {
        LaborGuideUnmappedOperationDto dto = new LaborGuideUnmappedOperationDto();
        dto.setSourceCode("MOCKGUIDE");
        dto.setProviderOpCode("MG-FOG-LAMP-ALIGN");
        dto.setOccurrenceCount(3);
        dto.setLastManifestId(MANIFEST_ID);
        when(ingestService.listUnmappedOperations()).thenReturn(List.of(dto));

        mockMvc.perform(get(BASE + "/unmapped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerOpCode").value("MG-FOG-LAMP-ALIGN"))
                .andExpect(jsonPath("$[0].occurrenceCount").value(3));
    }
}
