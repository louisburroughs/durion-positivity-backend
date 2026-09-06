package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.TreadDesignCandidateDto;
import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.dto.TreadDesignResolveRequest;
import com.positivity.catalog.internal.enums.MatchTier;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.TreadDesignService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract of the tread-design read and review endpoints (#1645).
 *
 * <p>Authorities are supplied per request through the gateway's {@code X-Authorities} header, which
 * is what {@code TestSecurityConfig} reads — so a request carrying the view permission but not the
 * resolve permission exercises the real distinction between reading a worklist and deciding it,
 * rather than a mocked one.
 */
@WebMvcTest(TreadDesignController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("/v1/catalog/tread-designs (CAP-324 #1352, review #1645)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class TreadDesignControllerTest {

    private static final UUID DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d01");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d02");
    private static final UUID VENDOR_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d03");
    private static final String BASE = "/v1/catalog/tread-designs";
    private static final String AUTHORITIES = "X-Authorities";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    TreadDesignService treadDesignService;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    private static TreadDesignDto dto(TreadDesignMatchState state) {
        return new TreadDesignDto(
                DESIGN_ID,
                VENDOR_PROFILE_ID,
                "michelin-eu",
                "VAR-1",
                "Michelin",
                "Pilot Sport 4S",
                null,
                "Michelin Pilot Sport 4S",
                "passenger",
                "summer",
                false,
                List.of(),
                List.of(),
                state,
                Instant.parse("2026-09-06T10:00:00Z"),
                null,
                null,
                null,
                List.of(),
                Instant.parse("2026-09-06T10:00:00Z"));
    }

    private static String resolveBody(String action, String extra) {
        return "{\"action\":\"" + action + "\"" + extra + "}";
    }

    @Nested
    @DisplayName("the review worklist")
    class Worklist {

        @Test
        @DisplayName("defaults to the states awaiting a decision")
        void defaultsToUnmatchedAndReview() throws Exception {
            when(treadDesignService.findForReview(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(dto(TreadDesignMatchState.REVIEW))));

            mockMvc.perform(get(BASE + "/unmatched").header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].matchState").value("REVIEW"));

            verify(treadDesignService)
                    .findForReview(
                            eq(List.of(TreadDesignMatchState.UNMATCHED, TreadDesignMatchState.REVIEW)),
                            isNull(),
                            any());
        }

        @Test
        @DisplayName("the requested states and vendor profile are passed through")
        void passesFiltersThrough() throws Exception {
            when(treadDesignService.findForReview(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get(BASE + "/unmatched")
                            .param("matchState", "REJECTED", "DEFERRED")
                            .param("vendorProfileId", VENDOR_PROFILE_ID.toString())
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isOk());

            verify(treadDesignService)
                    .findForReview(
                            eq(List.of(TreadDesignMatchState.REJECTED, TreadDesignMatchState.DEFERRED)),
                            eq(VENDOR_PROFILE_ID),
                            any());
        }

        @Test
        @DisplayName("an unknown match state is a 400 envelope, not a 500")
        void unknownMatchStateIsBadRequest() throws Exception {
            mockMvc.perform(get(BASE + "/unmatched")
                            .param("matchState", "NOT_A_STATE")
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("a page size beyond the bound is a 400 envelope")
        void oversizePageIsBadRequest() throws Exception {
            mockMvc.perform(get(BASE + "/unmatched")
                            .param("size", "500")
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("without the view permission the worklist is forbidden")
        void withoutPermissionIsForbidden() throws Exception {
            mockMvc.perform(get(BASE + "/unmatched").header(AUTHORITIES, "catalog:product:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("listing one design's candidates")
    class Candidates {

        @Test
        @DisplayName("returns the scored candidates, best first")
        void returnsCandidates() throws Exception {
            when(treadDesignService.findCandidates(DESIGN_ID))
                    .thenReturn(
                            List.of(new TreadDesignCandidateDto(PRODUCT_ID, new BigDecimal("0.8421"), MatchTier.AUTO)));

            mockMvc.perform(get(BASE + "/" + DESIGN_ID + "/candidates")
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].productId").value(PRODUCT_ID.toString()))
                    .andExpect(jsonPath("$[0].score").value(0.8421))
                    .andExpect(jsonPath("$[0].tier").value("AUTO"));
        }

        @Test
        @DisplayName("an unknown design is a 404 envelope")
        void unknownDesignIsNotFound() throws Exception {
            when(treadDesignService.findCandidates(DESIGN_ID))
                    .thenThrow(new CatalogNotFoundException("Tread design not found: " + DESIGN_ID));

            mockMvc.perform(get(BASE + "/" + DESIGN_ID + "/candidates")
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("without the view permission the candidates are forbidden")
        void withoutPermissionIsForbidden() throws Exception {
            mockMvc.perform(get(BASE + "/" + DESIGN_ID + "/candidates").header(AUTHORITIES, "catalog:product:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("resolving a design")
    class Resolve {

        @Test
        @DisplayName("an ATTACH returns the design in its new state and records who decided")
        void attachReturnsTheResolvedDesign() throws Exception {
            when(treadDesignService.resolve(eq(DESIGN_ID), any(), any()))
                    .thenReturn(dto(TreadDesignMatchState.MATCHED));

            mockMvc.perform(post(BASE + "/" + DESIGN_ID + "/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody(
                                    "ATTACH", ",\"productIds\":[\"" + PRODUCT_ID + "\"],\"note\":\"confirmed\""))
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_RESOLVE)
                            .header("X-User", "reviewer@example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.matchState").value("MATCHED"));

            ArgumentCaptor<TreadDesignResolveRequest> captor = ArgumentCaptor.forClass(TreadDesignResolveRequest.class);
            verify(treadDesignService).resolve(eq(DESIGN_ID), captor.capture(), eq("reviewer@example.com"));
            org.assertj.core.api.Assertions.assertThat(captor.getValue().productIds())
                    .containsExactly(PRODUCT_ID);
        }

        @Test
        @DisplayName("a body with no action is a 400 envelope")
        void missingActionIsBadRequest() throws Exception {
            mockMvc.perform(post(BASE + "/" + DESIGN_ID + "/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_RESOLVE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("an action and payload that cannot go together is a 400 envelope")
        void invalidCombinationIsBadRequest() throws Exception {
            when(treadDesignService.resolve(eq(DESIGN_ID), any(), any()))
                    .thenThrow(new CatalogValidationException("ATTACH requires at least one productId"));

            mockMvc.perform(post(BASE + "/" + DESIGN_ID + "/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody("ATTACH", ",\"productIds\":[]"))
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_RESOLVE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("an unknown design or product is a 404 envelope")
        void unknownDesignIsNotFound() throws Exception {
            when(treadDesignService.resolve(eq(DESIGN_ID), any(), any()))
                    .thenThrow(new CatalogNotFoundException("Tread design not found: " + DESIGN_ID));

            mockMvc.perform(post(BASE + "/" + DESIGN_ID + "/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody("REJECT", ""))
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_RESOLVE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("attaching a product another design already holds by hand is a 409 envelope")
        void manualConflictIsConflict() throws Exception {
            when(treadDesignService.resolve(eq(DESIGN_ID), any(), any()))
                    .thenThrow(new CatalogBusinessRuleException(
                            "Product " + PRODUCT_ID + " is already manually attached to tread design"));

            mockMvc.perform(post(BASE + "/" + DESIGN_ID + "/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody("ATTACH", ",\"productIds\":[\"" + PRODUCT_ID + "\"]"))
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_RESOLVE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("the view permission does not carry the right to decide")
        void viewPermissionCannotResolve() throws Exception {
            mockMvc.perform(post(BASE + "/" + DESIGN_ID + "/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resolveBody("REJECT", ""))
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("the product-scoped read")
    class ForProduct {

        @Test
        @DisplayName("a product matching no design is a bodiless 404")
        void unmatchedProductIsNotFound() throws Exception {
            when(treadDesignService.findForProduct(PRODUCT_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/for-product/" + PRODUCT_ID)
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a matched product returns the vendor's enrichment")
        void matchedProductReturnsEnrichment() throws Exception {
            when(treadDesignService.findForProduct(PRODUCT_ID))
                    .thenReturn(Optional.of(dto(TreadDesignMatchState.MATCHED)));

            mockMvc.perform(get(BASE + "/for-product/" + PRODUCT_ID)
                            .header(AUTHORITIES, CatalogPermissions.TREAD_DESIGN_VIEW))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.brand").value("Michelin"))
                    .andExpect(jsonPath("$.matchState").value("MATCHED"));
        }
    }
}
