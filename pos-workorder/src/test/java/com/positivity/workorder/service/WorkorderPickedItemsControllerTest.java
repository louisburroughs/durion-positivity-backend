package com.positivity.workorder.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.config.GlobalExceptionHandler;
import com.positivity.workorder.internal.controller.WorkorderPickedItemsController;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickedItemResponse;
import com.positivity.workorder.internal.enums.ConsumeItemStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-layer tests for CAP-218 picked-items endpoints in
 * {@link WorkorderPickedItemsController}.
 *
 * <p>
 * Covers HTTP routing, 200/404 service responses, and @PreAuthorize 403
 * enforcement for the picked-items and consume endpoints.
 *
 * Issue: CAP-218
 */
@WebMvcTest(
        controllers = WorkorderPickedItemsController.class,
        excludeFilters =
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = GlobalExceptionHandler.class))
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class WorkorderPickedItemsControllerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000218");
    private static final UUID PICK_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000219");

    private static final String PICKED_ITEMS_URL = "/v1/workorders/{workorderId}/picked-items";
    private static final String CONSUME_URL = "/v1/workorders/{workorderId}/picked-items:consume";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkorderPickFacadeService workorderPickFacadeService;

    // -------------------------------------------------------------------------
    // AC-1: GET /picked-items → 200 with list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: GET /picked-items returns 200 with picked item list")
    void getPickedItems_whenServiceReturnsList_returns200() throws Exception {
        // Issue CAP-218: happy path — service returns a list of
        // WorkorderPickedItemResponse
        var item = WorkorderPickedItemResponse.builder()
                .pickTaskId(PICK_TASK_ID)
                .pickListId(UUID.randomUUID())
                .skuId(UUID.randomUUID())
                .qtyPicked(3)
                .qtyConsumed(0)
                .qtyRemaining(3)
                .status("PICKED")
                .build();
        when(workorderPickFacadeService.getPickedItemsForWorkorder(WORKORDER_ID))
                .thenReturn(List.of(item));

        mockMvc.perform(get(PICKED_ITEMS_URL, WORKORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pickTaskId").value(PICK_TASK_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("PICKED"))
                .andExpect(jsonPath("$[0].qtyPicked").value(3));
    }

    // -------------------------------------------------------------------------
    // AC-2: GET /picked-items → 403 when missing inventory:pick_list:view
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: GET /picked-items returns 403 when missing inventory:pick_list:view authority")
    void getPickedItems_whenMissingViewAuthority_returns403() throws Exception {
        // Issue CAP-218: @PreAuthorize("hasAuthority('inventory:pick_list:view')") must
        // deny
        mockMvc.perform(get(PICKED_ITEMS_URL, WORKORDER_ID).with(req -> {
                    req.setAttribute(TestSecurityConfig.NO_PICK_AUTH_ATTR, Boolean.TRUE);
                    return req;
                }))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // AC-3: POST /picked-items:consume → 200 with consume response
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: POST /picked-items:consume returns 200 with ConsumePickedItemsResponse")
    void consumePickedItems_whenServiceReturnsResponse_returns200() throws Exception {
        // Issue CAP-218: happy path — items consumed, returns totals and per-item
        // results
        var item = ConsumePickedItemsRequest.ConsumeItem.builder()
                .pickTaskId(PICK_TASK_ID)
                .quantityToConsume(3)
                .build();
        var request = ConsumePickedItemsRequest.builder().items(List.of(item)).build();

        var result = ConsumePickedItemsResponse.ConsumedItemResult.builder()
                .pickTaskId(PICK_TASK_ID)
                .quantityConsumed(3)
                .status(ConsumeItemStatus.SUCCESS)
                .build();
        var response = ConsumePickedItemsResponse.builder()
                .workorderId(WORKORDER_ID)
                .totalItemsConsumed(3)
                .results(List.of(result))
                .build();
        when(workorderPickFacadeService.consumePickedItems(eq(WORKORDER_ID), any()))
                .thenReturn(response);

        mockMvc.perform(post(CONSUME_URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workorderId").value(WORKORDER_ID.toString()))
                .andExpect(jsonPath("$.totalItemsConsumed").value(3))
                .andExpect(jsonPath("$.results[0].status").value("SUCCESS"));
    }

    // -------------------------------------------------------------------------
    // AC-4: POST /picked-items:consume → 403 when missing workorder:parts:consume
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: POST /picked-items:consume returns 403 when missing workorder:parts:consume authority")
    void consumePickedItems_whenMissingConsumeAuthority_returns403() throws Exception {
        // Issue CAP-218: @PreAuthorize("hasAuthority('workorder:parts:consume')") must
        // deny
        var item = ConsumePickedItemsRequest.ConsumeItem.builder()
                .pickTaskId(PICK_TASK_ID)
                .quantityToConsume(1)
                .build();
        var request = ConsumePickedItemsRequest.builder().items(List.of(item)).build();

        mockMvc.perform(post(CONSUME_URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(req -> {
                            req.setAttribute(TestSecurityConfig.NO_PICK_AUTH_ATTR, Boolean.TRUE);
                            return req;
                        }))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // AC-5: POST /picked-items:consume → 404 when service throws NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: POST /picked-items:consume returns 404 when workorder not found")
    void consumePickedItems_whenWorkorderNotFound_returns404() throws Exception {
        // Issue CAP-218: service throws NOT_FOUND → controller propagates 404
        var item = ConsumePickedItemsRequest.ConsumeItem.builder()
                .pickTaskId(PICK_TASK_ID)
                .quantityToConsume(1)
                .build();
        var request = ConsumePickedItemsRequest.builder().items(List.of(item)).build();
        when(workorderPickFacadeService.consumePickedItems(eq(WORKORDER_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workorder not found"));

        mockMvc.perform(post(CONSUME_URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
