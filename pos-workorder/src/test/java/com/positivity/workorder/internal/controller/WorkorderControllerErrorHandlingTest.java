package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import com.positivity.workorder.internal.dto.CompleteWorkorderRequest;
import com.positivity.workorder.internal.service.WorkorderCountService;
import com.positivity.workorder.internal.service.WorkorderInvoiceService;
import com.positivity.workorder.internal.service.WorkorderService;
import java.util.UUID;
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

/**
 * Controller-slice regression coverage for issue #1694: {@link WorkorderController#completeWorkorder}
 * must never answer a genuine server-side anomaly as a 404. Modeled on
 * {@code pos-shop-manager}'s {@code ShopAuditControllerErrorHandlingTest}.
 */
@WebMvcTest(WorkorderController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class WorkorderControllerErrorHandlingTest {

    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000201");
    private static final String URL = "/v1/workorders/{workorderId}/complete";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkorderService workorderService;

    @MockitoBean
    private WorkorderInvoiceService workorderInvoiceService;

    @MockitoBean
    private WorkorderCountService workorderCountService;

    @Test
    @WithMockUser(authorities = "workorder:workorder:complete")
    void aBareIllegalArgumentExceptionAfterCompletionAnswers500NotNotFound() throws Exception {
        // (issue #1694) The post-completion re-fetch in WorkorderServiceImpl.completeWorkorder throws
        // a bare IllegalArgumentException on a genuine server-side anomaly (e.g. a concurrent delete of
        // a row this same transaction just saved) -- never a client input problem. It must not be
        // reported as 404, and must not leak internal detail into the response body.
        String leakCanary = "Workorder not found after completion: " + WORKORDER_ID;
        when(workorderService.getCurrentWorkorderStatus(WORKORDER_ID)).thenReturn("WORK_IN_PROGRESS");
        doAnswerThrow(leakCanary);

        CompleteWorkorderRequest request =
                CompleteWorkorderRequest.builder().completionNotes("all done").build();

        String body = mockMvc.perform(post(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("after completion")
                .contains("correlationId");
    }

    private void doAnswerThrow(String message) {
        org.mockito.Mockito.doThrow(new IllegalArgumentException(message))
                .when(workorderService)
                .completeWorkorder(any(), anyString(), any());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {}
}
