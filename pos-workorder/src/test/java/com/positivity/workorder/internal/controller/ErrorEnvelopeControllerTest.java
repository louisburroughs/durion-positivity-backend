package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import com.positivity.workorder.internal.service.ApprovalConfigurationService;
import com.positivity.workorder.internal.service.ChangeRequestService;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.FleetAuthorizationService;
import com.positivity.workorder.internal.service.IdempotencyService;
import com.positivity.workorder.internal.service.LocationHierarchyService;
import com.positivity.workorder.internal.service.TechnicianAssignmentService;
import com.positivity.workorder.internal.service.WorkexecTimeTrackingService;
import com.positivity.workorder.internal.service.WorkorderCountService;
import com.positivity.workorder.internal.service.WorkorderInvoiceService;
import com.positivity.workorder.internal.service.WorkorderLaborService;
import com.positivity.workorder.internal.service.WorkorderPartAdjustmentService;
import com.positivity.workorder.internal.service.WorkorderService;
import com.positivity.workorder.internal.service.WorkorderSubstitutionService;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Issue #1720 (ADR-0017 §3): every error these controllers used to answer with an empty body —
 * {@code ResponseEntity.notFound().build()}, {@code badRequest().build()} — now answers the
 * {@code ApiError} envelope its OpenAPI declares, with the same status, a machine code and a
 * correlation id.
 */
@WebMvcTest({
    ApprovalConfigurationController.class,
    ChangeRequestController.class,
    TechnicianAssignmentController.class,
    WorkorderController.class,
    EstimateController.class,
    WorkexecTimeTrackingController.class,
    WorkorderFleetAuthorizationController.class,
    WorkorderLaborController.class,
    WorkorderPartAdjustmentController.class
})
@Import({WebCommonErrorAutoConfiguration.class, ErrorEnvelopeControllerTest.SliceTestConfig.class})
@DisplayName("Former bodiless error responses answer the ApiError envelope (#1720)")
class ErrorEnvelopeControllerTest {

    private static final UUID ID = UUID.fromString("019200aa-0000-7000-8000-000000000401");
    private static final UUID OTHER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000402");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovalConfigurationService approvalConfigurationService;

    @MockitoBean
    private ChangeRequestService changeRequestService;

    @MockitoBean
    private TechnicianAssignmentService assignmentService;

    @MockitoBean
    private WorkorderService workorderService;

    @MockitoBean
    private WorkorderInvoiceService workorderInvoiceService;

    @MockitoBean
    private WorkorderCountService workorderCountService;

    @MockitoBean
    private FleetAuthorizationService fleetAuthorizationService;

    @MockitoBean
    private WorkorderLaborService laborService;

    @MockitoBean
    private WorkorderPartAdjustmentService adjustmentService;

    @MockitoBean
    private WorkorderSubstitutionService substitutionService;

    @MockitoBean
    private EstimateService estimateService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private LocationHierarchyService locationHierarchyService;

    @MockitoBean
    private WorkexecTimeTrackingService workexecTimeTrackingService;

    private static void expectEnvelope(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    @WithMockUser(authorities = "workorder:approval_config:view")
    void approvalConfigurationById404() throws Exception {
        when(approvalConfigurationService.getConfigurationById(ID)).thenReturn(Optional.empty());
        expectEnvelope(
                mockMvc.perform(get("/v1/workexec/approvalConfigurations/{id}", ID)),
                404,
                "APPROVAL_CONFIGURATION_NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "workorder:approval_config:view")
    void applicableApprovalConfiguration404() throws Exception {
        when(approvalConfigurationService.getApplicableConfiguration(null, null))
                .thenReturn(Optional.empty());
        expectEnvelope(
                mockMvc.perform(get("/v1/workexec/approvalConfigurations/applicable")),
                404,
                "APPROVAL_CONFIGURATION_NOT_FOUND");
    }

    @Test
    @WithMockUser
    void changeRequestInvalidState400() throws Exception {
        doThrow(new IllegalStateException("not declined"))
                .when(changeRequestService)
                .recordCustomerDenialAcknowledgment(ID);
        expectEnvelope(
                mockMvc.perform(post("/v1/workorders/changeRequests/{id}/acknowledgeDenial", ID)),
                400,
                "CHANGE_REQUEST_INVALID_STATE");
    }

    @Test
    @WithMockUser(authorities = "workorder:workorder:view")
    void technicianAssignmentMissing404() throws Exception {
        when(assignmentService.getCurrentAssignment(ID)).thenReturn(Optional.empty());
        expectEnvelope(
                mockMvc.perform(get("/v1/workorders/{id}/technician", ID)), 404, "TECHNICIAN_ASSIGNMENT_NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "workorder:workorder:view")
    void technicianAssignmentWorkorderMissing404() throws Exception {
        when(assignmentService.getWorkorderStatus(ID)).thenThrow(new NoSuchElementException("no workorder"));
        expectEnvelope(mockMvc.perform(get("/v1/workorders/{id}/technician", ID)), 404, "NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "workorder:workorder:view")
    void workorderById404() throws Exception {
        when(workorderService.getWorkorderById(ID)).thenReturn(Optional.empty());
        expectEnvelope(mockMvc.perform(get("/v1/workorders/{id}", ID)), 404, "NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "workorder:workorder:complete")
    void completeServiceItemInvalidState400() throws Exception {
        when(workorderService.completeServiceItem(any(), any(), any()))
                .thenThrow(new IllegalStateException("already complete"));
        expectEnvelope(
                mockMvc.perform(post("/v1/workorders/{id}/services/{line}/complete", ID, OTHER_ID)),
                400,
                "WORKORDER_ITEM_INVALID_STATE");
    }

    @Test
    @WithMockUser(authorities = "workorder:fleet_auth:request")
    void fleetAuthorizationMissing404() throws Exception {
        when(fleetAuthorizationService.find(ID)).thenReturn(Optional.empty());
        expectEnvelope(
                mockMvc.perform(get("/v1/workorders/{id}/fleet-authorization", ID)),
                404,
                "FLEET_AUTHORIZATION_NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "workorder:fleet_auth:request")
    void fleetAuthorizationNotRequired404() throws Exception {
        when(fleetAuthorizationService.requestAuthorization(ID)).thenReturn(Optional.empty());
        expectEnvelope(
                mockMvc.perform(post("/v1/workorders/{id}/fleet-authorization/requests", ID)),
                404,
                "FLEET_AUTHORIZATION_NOT_REQUIRED");
    }

    @Test
    @WithMockUser(authorities = "workorder:labor:add")
    void stopLaborMissing404() throws Exception {
        when(laborService.stopLaborSession(any(), any())).thenThrow(new NoSuchElementException("no entry"));
        expectEnvelope(mockMvc.perform(post("/v1/workorders/{id}/labor/{entry}/stop", ID, OTHER_ID)), 404, "NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "workorder:labor:add")
    void stopLaborInvalidState400() throws Exception {
        when(laborService.stopLaborSession(any(), any())).thenThrow(new IllegalStateException("already stopped"));
        expectEnvelope(
                mockMvc.perform(post("/v1/workorders/{id}/labor/{entry}/stop", ID, OTHER_ID)),
                400,
                "LABOR_SESSION_INVALID_STATE");
    }

    @Test
    @WithMockUser(authorities = "workorder:parts:add")
    void returnUnusedMissingPart404() throws Exception {
        when(adjustmentService.returnUnusedQuantity(any(), any(), any(), anyString(), any(), any()))
                .thenThrow(new NoSuchElementException("no part"));
        expectEnvelope(
                mockMvc.perform(post("/v1/workorders/{id}/parts/returnUnused", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workorderPartId\":\"" + OTHER_ID + "\",\"quantity\":1,\"reason\":\"UNUSED\"}")),
                404,
                "NOT_FOUND");
    }

    /**
     * The PDF operation produces {@code application/pdf}; a caller lacking the permission still gets
     * the 403 ApiError as JSON, not a failed negotiation, when it asks with Accept: application/pdf.
     */
    @Test
    @WithMockUser(authorities = "workorder:estimate:create")
    void pdfWithoutPermissionAnswersJsonForbidden() throws Exception {
        mockMvc.perform(get("/v1/workorders/estimates/{id}/pdf", ID).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /** Formerly a {@code Map{code,message}} body; now the full envelope, code and message kept. */
    @Test
    @WithMockUser(authorities = "workorder:labor:add")
    void laborPerformedConflictAnswersApiError() throws Exception {
        when(workexecTimeTrackingService.recordLaborPerformed(any(), anyString()))
                .thenThrow(new WorkexecTimeTrackingService.WorkexecConflictException(
                        "WORKEXEC_CONFLICT_WORKORDER_STATE", "Workorder is cancelled"));
        mockMvc.perform(post("/v1/workexec/labor-performed")
                        .header("Idempotency-Key", "labor-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workorderId":"019200aa-0000-7000-8000-000000000401",
                                 "technicianId":"019200aa-0000-7000-8000-000000000402",
                                 "performedAt":"2026-08-13T16:30:00Z",
                                 "labor":{"quantity":1.5,"unit":"HOURS"},
                                 "source":{"system":"MOBILE_APP","sourceReferenceId":"job-4711"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("WORKEXEC_CONFLICT_WORKORDER_STATE"))
                .andExpect(jsonPath("$.message").value("Workorder is cancelled"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {}
}
