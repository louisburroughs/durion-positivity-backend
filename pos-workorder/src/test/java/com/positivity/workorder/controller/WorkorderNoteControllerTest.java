package com.positivity.workorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.controller.WorkorderNoteController;
import com.positivity.workorder.internal.dto.AddWorkorderNoteRequest;
import com.positivity.workorder.internal.dto.WorkorderNoteResponse;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.service.WorkorderNoteService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for {@link WorkorderNoteController} (#1584).
 *
 * <p>The one that matters is {@code authorRecordedFromTheGatewayIdentity}: the author is taken from
 * the request's identity and never from the body, because it is republished as
 * {@code WorkorderNoteAddedV1.authoredBy} and lands on the customer's CRM timeline.
 */
@WebMvcTest(WorkorderNoteController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class WorkorderNoteControllerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");
    private static final UUID NOTE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000102");
    private static final String URL = "/v1/workorders/{workorderId}/notes";
    private static final String NOTE_TEXT = "Customer says the noise only happens on a cold start.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkorderNoteService workorderNoteService;

    private static WorkorderNoteResponse response() {
        return new WorkorderNoteResponse(
                NOTE_ID, WORKORDER_ID, "CUSTOMER_REQUEST", NOTE_TEXT, "advisor@example.com", Instant.now());
    }

    @Test
    @DisplayName("POST returns 201 with the saved note")
    void createReturns201() throws Exception {
        when(workorderNoteService.addNote(eq(WORKORDER_ID), any(), any())).thenReturn(response());

        mockMvc.perform(post(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AddWorkorderNoteRequest("CUSTOMER_REQUEST", NOTE_TEXT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.noteId").value(NOTE_ID.toString()))
                .andExpect(jsonPath("$.noteText").value(NOTE_TEXT));
    }

    @Test
    @DisplayName("The author comes from the request identity, not from the body")
    void authorRecordedFromTheGatewayIdentity() throws Exception {
        when(workorderNoteService.addNote(eq(WORKORDER_ID), any(), any())).thenReturn(response());

        mockMvc.perform(post(URL, WORKORDER_ID)
                        .header("X-User", "advisor@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddWorkorderNoteRequest(null, NOTE_TEXT))))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> author = ArgumentCaptor.forClass(String.class);
        verify(workorderNoteService).addNote(eq(WORKORDER_ID), any(), author.capture());
        assertThat(author.getValue()).isEqualTo("advisor@example.com");
    }

    @Test
    @DisplayName("A blank note is rejected as 400 before the service is reached")
    void blankNoteIsRejected() throws Exception {
        mockMvc.perform(post(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddWorkorderNoteRequest("NOTE", "   "))))
                .andExpect(status().isBadRequest());

        verify(workorderNoteService, never()).addNote(any(), any(), any());
    }

    @Test
    @DisplayName("A caller without workorder:note:add is refused")
    void createRequiresPermission() throws Exception {
        mockMvc.perform(post(URL, WORKORDER_ID)
                        .header("X-Authorities", "workorder:note:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddWorkorderNoteRequest(null, NOTE_TEXT))))
                .andExpect(status().isForbidden());

        verify(workorderNoteService, never()).addNote(any(), any(), any());
    }

    @Test
    @DisplayName("GET returns the workorder's notes")
    void listReturns200() throws Exception {
        when(workorderNoteService.listNotes(WORKORDER_ID)).thenReturn(List.of(response()));

        mockMvc.perform(get(URL, WORKORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noteId").value(NOTE_ID.toString()));
    }

    @Test
    @DisplayName("GET on an unknown workorder is 404")
    void listUnknownWorkorderIs404() throws Exception {
        when(workorderNoteService.listNotes(WORKORDER_ID)).thenThrow(new WorkorderNotFoundException(WORKORDER_ID));

        mockMvc.perform(get(URL, WORKORDER_ID)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A caller without workorder:note:view is refused")
    void listRequiresPermission() throws Exception {
        mockMvc.perform(get(URL, WORKORDER_ID).header("X-Authorities", "workorder:note:add"))
                .andExpect(status().isForbidden());

        verify(workorderNoteService, never()).listNotes(any());
    }
}
