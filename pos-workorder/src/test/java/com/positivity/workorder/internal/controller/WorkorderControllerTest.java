package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shared.dto.CountResponse;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.workorder.internal.dto.ApproveWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderResponse;
import com.positivity.workorder.internal.dto.CreateWorkorderRequest;
import com.positivity.workorder.internal.dto.ReopenWorkorderRequest;
import com.positivity.workorder.internal.dto.ReopenWorkorderResponse;
import com.positivity.workorder.internal.dto.WorkorderItemCompletionResponse;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.service.WorkorderStateMachine;
import com.positivity.workorder.service.WorkorderCountService;
import com.positivity.workorder.service.WorkorderInvoiceService;
import com.positivity.workorder.service.WorkorderService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Workorder endpoints: reads project the entity, writes delegate to the service, and each
 * failure mode gets its own status — a missing workorder is a 404, an illegal transition a 400.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderController")
class WorkorderControllerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa11");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa12");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa13");
    private static final UUID SERVICE_LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa14");
    private static final UUID PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa15");
    private static final UUID INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3faa16");

    @Mock
    private WorkorderService workorderService;

    @Mock
    private WorkorderInvoiceService workorderInvoiceService;

    @Mock
    private WorkorderCountService workorderCountService;

    @InjectMocks
    private WorkorderController controller;

    private Workorder workorder;

    @BeforeEach
    void setUp() {
        workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setEstimateId(ESTIMATE_ID);
        workorder.setCustomerId(CUSTOMER_ID);
        workorder.setStatus(WorkorderStatus.DRAFT);
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void listsEveryWorkorder() {
            when(workorderService.getAllWorkorders()).thenReturn(List.of(workorder));

            List<WorkorderResponse> responses = controller.getAllWorkorders();

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).getId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        void servesOneWorkorderById() {
            when(workorderService.getWorkorderById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

            ResponseEntity<WorkorderResponse> response = controller.getWorkorderById(WORKORDER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        void reportsNotFoundForAnUnknownWorkorder() {
            when(workorderService.getWorkorderById(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThat(controller.getWorkorderById(WORKORDER_ID).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void countsTheExplicitlyRequestedStatuses() {
            CountResponse expected = new CountResponse(3, List.of());
            when(workorderCountService.countByStatuses(Set.of(WorkorderStatus.DRAFT)))
                    .thenReturn(expected);

            assertThat(controller.countWorkorders(false, Set.of(WorkorderStatus.DRAFT)))
                    .isEqualTo(expected);
        }

        @Test
        void countsOnlyOpenStatusesWhenAskedTo() {
            when(workorderCountService.countByStatuses(WorkorderStatus.getOpenStatuses()))
                    .thenReturn(new CountResponse(2, List.of()));

            assertThat(controller.countWorkorders(true, null).total()).isEqualTo(2);
        }

        @Test
        void countsEveryStatusWhenNeitherFilterIsGiven() {
            when(workorderCountService.countByStatuses(null)).thenReturn(new CountResponse(9, List.of()));

            assertThat(controller.countWorkorders(false, Set.of()).total()).isEqualTo(9);
            verify(workorderCountService, org.mockito.Mockito.atLeastOnce()).countByStatuses(null);
        }

        @Test
        void servesTheTransitionAndSnapshotHistories() {
            when(workorderService.getTransitionHistory(WORKORDER_ID)).thenReturn(List.of());
            when(workorderService.getSnapshotHistory(WORKORDER_ID)).thenReturn(List.of());

            assertThat(controller.getTransitionHistory(WORKORDER_ID).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(controller.getSnapshotHistory(WORKORDER_ID).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("create and delete")
    class CreateAndDelete {

        @Test
        void createsAWorkorderThroughTheIdempotentServicePath() {
            when(workorderService.createWorkorderWithIdempotency(ESTIMATE_ID, CUSTOMER_ID, "key-1"))
                    .thenReturn(workorder);

            ResponseEntity<WorkorderResponse> response = controller.createWorkorder(
                    CreateWorkorderRequest.builder()
                            .estimateId(ESTIMATE_ID)
                            .customerId(CUSTOMER_ID)
                            .build(),
                    "key-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        void deletesAWorkorder() {
            assertThat(controller.deleteWorkorder(WORKORDER_ID).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(workorderService).deleteWorkorder(WORKORDER_ID);
        }
    }

    @Nested
    @DisplayName("approve, complete and reopen")
    class Lifecycle {

        @Test
        void approvesAWorkorderWithItsCapturedSignature() {
            when(workorderService.approveWorkorder(
                            WORKORDER_ID, CUSTOMER_ID, "sig", "image/png", "Jane Customer", "ok"))
                    .thenReturn(workorder);

            ResponseEntity<WorkorderResponse> response = controller.approveWorkorder(
                    WORKORDER_ID,
                    ApproveWorkorderRequest.builder()
                            .customerId(CUSTOMER_ID)
                            .signatureData("sig")
                            .signerName("Jane Customer")
                            .notes("ok")
                            .build());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void reportsBadRequestWhenAnApprovalIsNotAllowed() {
            ApproveWorkorderRequest request =
                    ApproveWorkorderRequest.builder().customerId(CUSTOMER_ID).build();
            doThrow(new IllegalStateException("not a draft"))
                    .when(workorderService)
                    .approveWorkorder(any(), any(), any(), any(), any(), any());

            assertThat(controller.approveWorkorder(WORKORDER_ID, request).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void completesAWorkorderAndReportsBothStatuses() {
            when(workorderService.getCurrentWorkorderStatus(WORKORDER_ID)).thenReturn("WORK_IN_PROGRESS");

            ResponseEntity<CompleteWorkorderResponse> response = controller.completeWorkorder(
                    WORKORDER_ID,
                    CompleteWorkorderRequest.builder()
                            .completionNotes("all done")
                            .build());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getPreviousStatus()).isEqualTo("WORK_IN_PROGRESS");
            assertThat(response.getBody().getCurrentStatus()).isEqualTo("COMPLETED");
            verify(workorderService).completeWorkorder(WORKORDER_ID, "system", "all done");
        }

        @Test
        void mapsCompletionFailuresOntoBadRequestAndNotFound() {
            CompleteWorkorderRequest request =
                    CompleteWorkorderRequest.builder().build();

            doThrow(new IllegalStateException("open change requests"))
                    .when(workorderService)
                    .completeWorkorder(any(), anyString(), any());
            assertThat(controller.completeWorkorder(WORKORDER_ID, request).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            doThrow(new IllegalArgumentException("no such workorder"))
                    .when(workorderService)
                    .completeWorkorder(any(), anyString(), any());
            assertThat(controller.completeWorkorder(WORKORDER_ID, request).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void reportsTheCompletionPreconditions() {
            when(workorderService.getCompletionPreconditions(WORKORDER_ID))
                    .thenReturn(new WorkorderStateMachine.CompletionPreconditions(
                            WORKORDER_ID,
                            "WORK_IN_PROGRESS",
                            false,
                            List.of("all parts installed"),
                            List.of("2 open change requests"),
                            2,
                            1,
                            0,
                            false,
                            true));

            ResponseEntity<?> response = controller.getCompletionPreconditions(WORKORDER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().toString()).contains("2 open change requests");
        }

        @Test
        void reportsNotFoundForPreconditionsOfAnUnknownWorkorder() {
            doThrow(new IllegalArgumentException("no such workorder"))
                    .when(workorderService)
                    .getCompletionPreconditions(WORKORDER_ID);

            assertThat(controller.getCompletionPreconditions(WORKORDER_ID).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void reopensACompletedWorkorder() {
            when(workorderService.reopenCompletedWorkorder(eq(WORKORDER_ID), anyString(), eq("more work")))
                    .thenReturn(new WorkorderService.ReopenResult(
                            WORKORDER_ID, "COMPLETED", true, Instant.parse("2026-03-01T12:00:00Z")));

            ResponseEntity<ReopenWorkorderResponse> response = controller.reopenWorkorder(
                    WORKORDER_ID,
                    ReopenWorkorderRequest.builder().reopenReason("more work").build());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getIsReopened()).isTrue();
            assertThat(response.getBody().getMessage()).isEqualTo("Workorder reopened successfully");
        }

        @Test
        void mapsReopenFailuresOntoNotFoundAndBadRequest() {
            ReopenWorkorderRequest request =
                    ReopenWorkorderRequest.builder().reopenReason("more work").build();

            doThrow(new IllegalArgumentException("no such workorder"))
                    .when(workorderService)
                    .reopenCompletedWorkorder(any(), anyString(), any());
            assertThat(controller.reopenWorkorder(WORKORDER_ID, request).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new IllegalStateException("not completed"))
                    .when(workorderService)
                    .reopenCompletedWorkorder(any(), anyString(), any());
            ResponseEntity<ReopenWorkorderResponse> badRequest = controller.reopenWorkorder(WORKORDER_ID, request);
            assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(badRequest.getBody().getMessage()).isEqualTo("not completed");
        }
    }

    @Nested
    @DisplayName("invoice and item completion")
    class InvoiceAndItems {

        @Test
        void reportsAcceptedWhileInvoiceGenerationIsStillPending() {
            when(workorderInvoiceService.generateInvoice(WORKORDER_ID, "key-1"))
                    .thenReturn(InvoiceGenerationResponse.builder()
                            .workorderId(WORKORDER_ID)
                            .status(WorkorderInvoiceService.STATUS_PENDING)
                            .build());

            assertThat(controller.generateInvoice(WORKORDER_ID, "key-1").getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }

        @Test
        void reportsOkOnceTheInvoiceExists() {
            when(workorderInvoiceService.generateInvoice(WORKORDER_ID, null))
                    .thenReturn(InvoiceGenerationResponse.builder()
                            .workorderId(WORKORDER_ID)
                            .invoiceId(INVOICE_ID)
                            .status("GENERATED")
                            .build());

            ResponseEntity<InvoiceGenerationResponse> response = controller.generateInvoice(WORKORDER_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getInvoiceId()).isEqualTo(INVOICE_ID);
        }

        @Test
        void completesAServiceLine() {
            when(workorderService.completeServiceItem(eq(WORKORDER_ID), eq(SERVICE_LINE_ID), anyString()))
                    .thenReturn(WorkorderItemCompletionResponse.builder()
                            .workorderId(WORKORDER_ID)
                            .itemId(SERVICE_LINE_ID)
                            .build());

            assertThat(controller
                            .completeServiceItem(WORKORDER_ID, SERVICE_LINE_ID)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void mapsServiceLineFailuresOntoNotFoundAndBadRequest() {
            doThrow(new IllegalArgumentException("no such line"))
                    .when(workorderService)
                    .completeServiceItem(any(), any(), anyString());
            assertThat(controller
                            .completeServiceItem(WORKORDER_ID, SERVICE_LINE_ID)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new IllegalStateException("line is cancelled"))
                    .when(workorderService)
                    .completeServiceItem(any(), any(), anyString());
            assertThat(controller
                            .completeServiceItem(WORKORDER_ID, SERVICE_LINE_ID)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void completesAPartLine() {
            when(workorderService.completePartItem(eq(WORKORDER_ID), eq(PART_ID), anyString()))
                    .thenReturn(WorkorderItemCompletionResponse.builder()
                            .workorderId(WORKORDER_ID)
                            .itemId(PART_ID)
                            .build());

            assertThat(controller.completePartItem(WORKORDER_ID, PART_ID).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        void mapsPartLineFailuresOntoNotFoundAndBadRequest() {
            doThrow(new IllegalArgumentException("no such part"))
                    .when(workorderService)
                    .completePartItem(any(), any(), anyString());
            assertThat(controller.completePartItem(WORKORDER_ID, PART_ID).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new IllegalStateException("part is cancelled"))
                    .when(workorderService)
                    .completePartItem(any(), any(), anyString());
            assertThat(controller.completePartItem(WORKORDER_ID, PART_ID).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
