package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import com.positivity.workorder.internal.service.WorkorderPickFacadeService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * End-to-end proof for issue #2076 on the endpoint that surfaced it: a workorder with no pick list
 * replica must be reported as a missing <em>resource</em>, not as a missing route. The service
 * throws {@code ResponseStatusException(NOT_FOUND, "No pick list found for workorder …")}, no
 * advice in this module maps that type, and the platform fallback used to answer every 404
 * {@code ErrorResponse} with "No endpoint for the requested path" — which sent the frontend
 * (durion-positivity-frontend#286) to debug routing for an endpoint that had matched all along.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration},
 * so {@link WebCommonErrorAutoConfiguration} is imported explicitly to exercise the real fallback
 * chain, as {@link WorkorderDetailControllerErrorHandlingTest} explains.
 */
@WebMvcTest(WorkorderPickFacadeController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class WorkorderPickFacadeControllerErrorHandlingTest {

    private static final UUID WORKORDER_ID = UUID.fromString("01a0a52c-89f9-7ef9-9c97-583da36fa240");
    private static final String URL = "/v1/workorders/{workorderId}/pick-list";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkorderPickFacadeService workorderPickFacadeService;

    @Test
    @WithMockUser(authorities = "inventory:pick_list:view")
    void aWorkorderWithNoPickListAnswers404AsAMissingResourceNotAMissingEndpoint() throws Exception {
        when(workorderPickFacadeService.getPickListForWorkorder(any(UUID.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No pick list found for workorder " + WORKORDER_ID));

        mockMvc.perform(get(URL, WORKORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Requested resource was not found"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    /** The reason embeds the id the client sent, so the envelope must not reflect it (S5131). */
    @Test
    @WithMockUser(authorities = "inventory:pick_list:view")
    void theMissingPickListBodyDoesNotEchoTheWorkorderId() throws Exception {
        when(workorderPickFacadeService.getPickListForWorkorder(any(UUID.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No pick list found for workorder " + WORKORDER_ID));

        String body = mockMvc.perform(get(URL, WORKORDER_ID))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(WORKORDER_ID.toString());
    }

    /**
     * #2225: the resolve-scan validation tests added alongside #2217 exercised a slice that
     * excludes this module's own {@link com.positivity.workorder.internal.config.GlobalExceptionHandler}
     * and imports only {@link WebCommonErrorAutoConfiguration}, so they proved the platform
     * fallback's {@code VALIDATION_ERROR} code rather than what production actually answers. This
     * module's own {@code MethodArgumentNotValidException} handler runs here (nothing excludes
     * it) and answers {@code VALIDATION_FAILED} — the code the resolvePickScan {@code @Operation}
     * description and {@code ResolveScanRequest}'s validation are documented against.
     */
    @Test
    @WithMockUser(authorities = "inventory:pick_list:execute")
    @DisplayName("#2225: resolve-scan validation runs through the real module advice and answers "
            + "400 VALIDATION_FAILED with fieldErrors")
    void resolveScanValidationAnswersValidationFailedThroughTheRealAdvice() throws Exception {
        String pickTaskId = "01a0a52c-89f9-7ef9-9c97-583da36fa241";
        // Neither scannedSkuId nor scannedProductCode, and neither scannedLocationId nor
        // scannedLocationCode: both @AssertTrue checks fail.
        mockMvc.perform(post(
                                "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}:resolve-scan",
                                WORKORDER_ID,
                                pickTaskId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("productTargetValid", "locationTargetValid")));
    }
}
