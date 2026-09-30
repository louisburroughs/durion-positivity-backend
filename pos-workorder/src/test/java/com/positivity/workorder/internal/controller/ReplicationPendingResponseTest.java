package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.web.common.ReplicationPendingException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import com.positivity.workorder.internal.service.TechnicianAssignmentService;
import com.positivity.workorder.internal.service.WorkorderPickFacadeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #1994: a {@link ReplicationPendingException} thrown below the controllers reaches the wire as
 * {@code 503} with {@code Retry-After} and its own code. This module's
 * {@link com.positivity.workorder.internal.config.GlobalExceptionHandler} runs here (nothing
 * excludes it) and maps neither {@code RuntimeException} nor {@code Exception}, so the platform
 * advice from {@code pos-web-common} renders it; see {@link WorkorderDetailControllerErrorHandlingTest}
 * for why {@link WebCommonErrorAutoConfiguration} is imported explicitly.
 */
@WebMvcTest({WorkorderPickFacadeController.class, TechnicianAssignmentController.class})
@Import(WebCommonErrorAutoConfiguration.class)
class ReplicationPendingResponseTest {

    private static final UUID WORKORDER_ID = UUID.fromString("01a0a52c-89f9-7ef9-9c97-583da36fa240");
    private static final UUID TECHNICIAN_ID = UUID.fromString("01a0a52c-89f9-7ef9-9c97-583da36fa250");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkorderPickFacadeService workorderPickFacadeService;

    @MockitoBean
    private TechnicianAssignmentService technicianAssignmentService;

    @Test
    @WithMockUser(authorities = "inventory:pick_list:view")
    void aPendingPickListIsA503WithRetryAfterAndItsCode() throws Exception {
        when(workorderPickFacadeService.getPickListForWorkorder(any(UUID.class)))
                .thenThrow(new ReplicationPendingException("PICK_LIST_REPLICATION_PENDING", "not yet", WORKORDER_ID));

        mockMvc.perform(get("/v1/workorders/{workorderId}/pick-list", WORKORDER_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("PICK_LIST_REPLICATION_PENDING"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.referenceId").value(WORKORDER_ID.toString()))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @WithMockUser(authorities = "workorder:workorder:assign-technician")
    void aPendingTechnicianIsA503WithRetryAfterAndItsCode() throws Exception {
        when(technicianAssignmentService.assignTechnician(any(), any(), any(), any()))
                .thenThrow(new ReplicationPendingException("TECHNICIAN_REPLICATION_PENDING", "not yet", TECHNICIAN_ID));

        mockMvc.perform(post("/v1/workorders/{workorderId}/technician", WORKORDER_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicianId\":\"" + TECHNICIAN_ID + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("TECHNICIAN_REPLICATION_PENDING"))
                .andExpect(jsonPath("$.referenceId").value(TECHNICIAN_ID.toString()));
    }
}
