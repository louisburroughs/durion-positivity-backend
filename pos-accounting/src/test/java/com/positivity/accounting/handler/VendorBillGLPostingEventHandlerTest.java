package com.positivity.accounting.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.VendorBillGLPostingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.handler.VendorBillGLPostingEventHandler;
import com.positivity.accounting.service.EventIngestionService;

/**
 * Unit tests for VendorBillGLPostingEventHandler.
 * Tests verify that VendorBillGLPostingEvent is correctly converted to
 * AccountingEvent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorBillGLPostingEventHandler")
@SuppressWarnings("unchecked")
class VendorBillGLPostingEventHandlerTest {

        @Mock
        private EventIngestionService eventIngestionService;

        @InjectMocks
        private VendorBillGLPostingEventHandler handler;

        private VendorBillGLPostingEvent testEvent;
        private UUID testBillId;
        private UUID testVendorId;
        private UUID testPoId;
        private UUID testProductId1;
        private UUID testProductId2;

        @BeforeEach
        void setUp() {
                testBillId = UUID.randomUUID();
                testVendorId = UUID.randomUUID();
                testPoId = UUID.randomUUID();
                testProductId1 = UUID.randomUUID();
                testProductId2 = UUID.randomUUID();

                testEvent = VendorBillGLPostingEvent.builder()
                                .eventId(UUID.randomUUID())
                                .organizationId(UUID.randomUUID())
                                .vendorBillId(testBillId)
                                .vendorId(testVendorId)
                                .vendorName("Test Vendor Inc")
                                .purchaseOrderId(testPoId)
                                .purchaseOrderNumber("PO-2026-00123")
                                .billNumber("BILL-2026-00456")
                                .billDate(LocalDateTime.of(2026, 2, 11, 10, 30))
                                .totalAmount(new BigDecimal("1300.00"))
                                .lineItems(List.of(
                                                VendorBillGLPostingEvent.BillLineItem.builder()
                                                                .productId(testProductId1)
                                                                .sku("WIDGET-A-001")
                                                                .description("Widget Type A")
                                                                .quantity(new BigDecimal("100.00"))
                                                                .unitPrice(new BigDecimal("12.50"))
                                                                .lineTotal(new BigDecimal("1250.00"))
                                                                .isInventoryItem(true)
                                                                .build(),
                                                VendorBillGLPostingEvent.BillLineItem.builder()
                                                                .productId(testProductId2)
                                                                .sku("SHIP-FEE")
                                                                .description("Shipping Fee")
                                                                .quantity(new BigDecimal("1.00"))
                                                                .unitPrice(new BigDecimal("50.00"))
                                                                .lineTotal(new BigDecimal("50.00"))
                                                                .isInventoryItem(false)
                                                                .build()))
                                .build();
        }

        @Test
        @DisplayName("Should submit accounting event when GL posting event received")
        void shouldSubmitAccountingEventWhenGLPostingEventReceived() {
                // Given: Event ingestion service returns success response
                AccountingEventResponse response = new AccountingEventResponse();
                response.setEventId(UUID.randomUUID());
                response.setStatus(AccountingEventStatus.RECEIVED);
                when(eventIngestionService.submitEvent(any(Map.class))).thenReturn(response);

                // When: Handling GL posting event
                handler.onVendorBillGLPosting(testEvent);

                // Then: Should submit accounting event
                verify(eventIngestionService).submitEvent(any(Map.class));
        }

        @Test
        @DisplayName("Should map event type correctly")
        void shouldMapEventTypeCorrectly() {
                // Given
                AccountingEventResponse response = new AccountingEventResponse();
                response.setEventId(UUID.randomUUID());
                response.setStatus(AccountingEventStatus.RECEIVED);
                when(eventIngestionService.submitEvent(any(Map.class))).thenReturn(response);

                // When
                handler.onVendorBillGLPosting(testEvent);

                // Then: Should use correct event type
                ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
                verify(eventIngestionService).submitEvent(captor.capture());

                Map<String, Object> payload = captor.getValue();
                assertThat(payload).containsEntry("eventType", "VENDOR_BILL_GL_POSTING")
                                .containsEntry("sourceSystem", "POS");
        }

        @Test
        @DisplayName("Should map vendor bill details correctly")
        void shouldMapVendorBillDetailsCorrectly() {
                // Given
                AccountingEventResponse response = new AccountingEventResponse();
                response.setEventId(UUID.randomUUID());
                response.setStatus(AccountingEventStatus.RECEIVED);
                when(eventIngestionService.submitEvent(any(Map.class))).thenReturn(response);

                // When
                handler.onVendorBillGLPosting(testEvent);

                // Then: Should map all bill details
                ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
                verify(eventIngestionService).submitEvent(captor.capture());

                Map<String, Object> payload = captor.getValue();

                Map<String, Object> billDetails = (Map<String, Object>) payload.get("payload");

                assertThat(billDetails).containsEntry("vendorBillId", testBillId)
                                .containsEntry("vendorId", testVendorId)
                                .containsEntry("vendorName", "Test Vendor Inc")
                                .containsEntry("purchaseOrderId", testPoId)
                                .containsEntry("purchaseOrderNumber", "PO-2026-00123")
                                .containsEntry("billNumber", "BILL-2026-00456")
                                .containsEntry("totalAmount", new BigDecimal("1300.00"));
        }

        @Test
        @DisplayName("Should preserve isInventoryItem flag in line items")
        void shouldPreserveIsInventoryItemFlagInLineItems() {
                // Given
                AccountingEventResponse response = new AccountingEventResponse();
                response.setEventId(UUID.randomUUID());
                response.setStatus(AccountingEventStatus.RECEIVED);
                when(eventIngestionService.submitEvent(any(Map.class))).thenReturn(response);

                // When
                handler.onVendorBillGLPosting(testEvent);

                // Then: isInventoryItem flag should be preserved
                ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
                verify(eventIngestionService).submitEvent(captor.capture());

                Map<String, Object> payload = captor.getValue();

                Map<String, Object> billDetails = (Map<String, Object>) payload.get("payload");

                List<Map<String, Object>> lineItems = (List<Map<String, Object>>) billDetails.get("lineItems");

                assertThat(lineItems).hasSize(2);

                // First line: Inventory item
                Map<String, Object> firstLine = lineItems.get(0);
                assertThat(firstLine).containsEntry("productId", testProductId1)
                                .containsEntry("isInventoryItem", true)
                                .containsEntry("lineTotal", new BigDecimal("1250.00"));

                // Second line: Non-inventory (expense) item
                Map<String, Object> secondLine = lineItems.get(1);
                assertThat(secondLine).containsEntry("productId", testProductId2)
                                .containsEntry("isInventoryItem", false)
                                .containsEntry("lineTotal", new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("Should map all line item fields correctly")
        void shouldMapAllLineItemFieldsCorrectly() {
                // Given
                AccountingEventResponse response = new AccountingEventResponse();
                response.setEventId(UUID.randomUUID());
                response.setStatus(AccountingEventStatus.RECEIVED);
                when(eventIngestionService.submitEvent(any(Map.class))).thenReturn(response);

                // When
                handler.onVendorBillGLPosting(testEvent);

                // Then: All line item fields should be mapped
                ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
                verify(eventIngestionService).submitEvent(captor.capture());

                Map<String, Object> payload = captor.getValue();

                Map<String, Object> billDetails = (Map<String, Object>) payload.get("payload");

                List<Map<String, Object>> lineItems = (List<Map<String, Object>>) billDetails.get("lineItems");

                Map<String, Object> firstLine = lineItems.get(0);
                assertThat(firstLine).containsEntry("productId", testProductId1)
                                .containsEntry("sku", "WIDGET-A-001")
                                .containsEntry("description", "Widget Type A")
                                .containsEntry("quantity", new BigDecimal("100.00"))
                                .containsEntry("unitPrice", new BigDecimal("12.50"))
                                .containsEntry("lineTotal", new BigDecimal("1250.00"))
                                .containsEntry("isInventoryItem", true);
        }

        @Test
        @DisplayName("Should throw exception when event ingestion fails")
        void shouldThrowExceptionWhenEventIngestionFails() {
                // Given: Event ingestion service throws exception
                when(eventIngestionService.submitEvent(any(Map.class)))
                                .thenThrow(new IllegalArgumentException("Event validation failed"));

                // When/Then: Should propagate exception
                assertThatThrownBy(() -> handler.onVendorBillGLPosting(testEvent))
                                .isInstanceOf(RuntimeException.class)
                                .hasMessageContaining("GL posting failed for bill: " + testBillId);
        }

        @Test
        @DisplayName("Should use transaction date from bill date")
        void shouldUseTransactionDateFromBillDate() {
                // Given
                AccountingEventResponse response = new AccountingEventResponse();
                response.setEventId(UUID.randomUUID());
                response.setStatus(AccountingEventStatus.RECEIVED);
                when(eventIngestionService.submitEvent(any(Map.class))).thenReturn(response);

                // When
                handler.onVendorBillGLPosting(testEvent);

                // Then: Transaction date should match bill date
                verify(eventIngestionService).submitEvent(argThat(payload -> {
                        LocalDateTime transactionDate = (LocalDateTime) payload.get("transactionDate");
                        return transactionDate != null
                                        && transactionDate.equals(LocalDateTime.of(2026, 2, 11, 10, 30));
                }));
        }
}
