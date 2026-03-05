package com.positivity.accounting.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;

/**
 * Contract Behavioral Integration Tests for AP Payments (CAP-053)
 *
 * <p>
 * This test suite validates the behavioral contracts defined in
 * durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md
 * Section: AP Payment endpoints (v1.0)
 *
 * <p>
 * Each test maps to a scenario defined in the contract guide's "Examples"
 * section for AP Payment operations. Tests run against the actual service in
 * test mode (not mocked).
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@DisplayName("AP Payment Backend Contract Behavioral Tests (CAP-053)")
class APPaymentContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private APPaymentRepository apPaymentRepository;

        @Autowired
        private VendorBillRepository vendorBillRepository;

        @Autowired
        private APPaymentAllocationRepository allocationRepository;

        private static final String API_V1_AP_PAYMENTS = "/v1/accounting/ap/payments";
        private static final String API_V1_AP_BILLS = "/v1/accounting/ap/bills";

        // Test data
        private UUID testVendorId;
        private VendorBill bill1; // Due oldest
        private VendorBill bill2; // Due newer

        @BeforeEach
        void setUp() {
                // Clean up any existing test data
                allocationRepository.deleteAll();
                apPaymentRepository.deleteAll();
                vendorBillRepository.deleteAll();

                // Setup test vendor and bills
                testVendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // Bill 1: Due oldest (30 days ago), $500
                bill1 = new VendorBill();
                bill1.setVendorId(testVendorId);
                bill1.setBillNumber("BILL-001");
                bill1.setBillDate(LocalDate.now(TEST_CLOCK).minusDays(40).atStartOfDay());
                bill1.setDueDate(LocalDate.now(TEST_CLOCK).minusDays(10).atStartOfDay());
                bill1.setTotalAmount(new BigDecimal("500.00"));
                bill1.setStatus(VendorBillStatus.APPROVED);
                bill1.setCreatedBy("test-setup");
                bill1.setModifiedBy("test-setup");
                bill1 = vendorBillRepository.save(bill1);

                // Bill 2: Due newer (15 days from now), $300
                bill2 = new VendorBill();
                bill2.setVendorId(testVendorId);
                bill2.setBillNumber("BILL-002");
                bill2.setBillDate(LocalDate.now(TEST_CLOCK).minusDays(10).atStartOfDay());
                bill2.setDueDate(LocalDate.now(TEST_CLOCK).plusDays(15).atStartOfDay());
                bill2.setTotalAmount(new BigDecimal("300.00"));
                bill2.setStatus(VendorBillStatus.APPROVED);
                bill2.setCreatedBy("test-setup");
                bill2.setModifiedBy("test-setup");
                bill2 = vendorBillRepository.save(bill2);
        }

        @AfterEach
        void tearDown() {
                // Clean up test data after each test
                allocationRepository.deleteAll();
                apPaymentRepository.deleteAll();
                vendorBillRepository.deleteAll();
        }

        // ===============================================
        // HAPPY PATH SCENARIOS (from Contract Guide Examples)
        // ===============================================

        @Test
        @DisplayName("CP-AP-001: Execute payment with automatic allocation (oldest due first)")
        void testExecutePayment_AutomaticAllocation_Success() throws Exception {
                // Arrange: Payment of $600 with no explicit allocations (automatic allocation
                // applies)
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                ExecuteAPPaymentRequest request = new ExecuteAPPaymentRequest();
                request.setVendorId(testVendorId);
                request.setGrossAmount(new BigDecimal("600.00"));
                request.setFeeAmount(BigDecimal.ZERO);
                request.setNetAmount(new BigDecimal("600.00"));
                request.setCurrency("USD");
                request.setPaymentRef(paymentRef);
                request.setPaymentMethod(PaymentMethod.ACH);
                request.setMemo("Automatic allocation test");
                request.setAllocations(List.of()); // Empty allocations triggers automatic

                // Act: POST /v1/accounting/ap/payments
                mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.paymentRef").value(paymentRef))
                                .andExpect(jsonPath("$.vendorId").value(testVendorId.toString()))
                                .andExpect(jsonPath("$.grossAmount").value(600.00))
                                .andExpect(jsonPath("$.netAmount").value(600.00))
                                .andExpect(jsonPath("$.status").value("GL_POST_PENDING"))
                                .andExpect(jsonPath("$.allocations").isNotEmpty())
                                .andReturn();

                // Assert: Verify automatic allocation (oldest due first: bill1 fully paid,
                // bill2 partially)
                APPayment savedPayment = apPaymentRepository.findByPaymentRef(paymentRef).orElseThrow();
                assertThat(savedPayment.getUnappliedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

                List<APPaymentAllocation> allocations = allocationRepository
                                .findByPaymentIdOrderByAllocationSequenceAsc(savedPayment.getPaymentId());
                assertThat(allocations).hasSize(2);

                // Verify bill1 (oldest due) gets allocated first: $500
                assertThat(allocations.get(0).getVendorBillId()).isEqualTo(bill1.getVendorBillId());
                assertThat(allocations.get(0).getAppliedAmount()).isEqualByComparingTo(new BigDecimal("500.00"));

                // Verify bill2 gets remaining $100
                assertThat(allocations.get(1).getVendorBillId()).isEqualTo(bill2.getVendorBillId());
                assertThat(allocations.get(1).getAppliedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("CP-AP-002: Execute payment with explicit allocations")
        void testExecutePayment_ExplicitAllocations_Success() throws Exception {
                // Arrange: Payment of $400 with explicit allocation to bill2 only
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                ExecuteAPPaymentRequest request = new ExecuteAPPaymentRequest();
                request.setVendorId(testVendorId);
                request.setGrossAmount(new BigDecimal("400.00"));
                request.setFeeAmount(new BigDecimal("5.00"));
                request.setNetAmount(new BigDecimal("395.00"));
                request.setCurrency("USD");
                request.setPaymentRef(paymentRef);
                request.setPaymentMethod(PaymentMethod.WIRE);
                request.setMemo("Explicit allocation to BILL-002 only");

                // Explicit allocation: pay bill2 $300, leave $100 unapplied
                ExecuteAPPaymentRequest.AllocationLineRequest allocation = new ExecuteAPPaymentRequest.AllocationLineRequest();
                allocation.setVendorBillId(bill2.getVendorBillId());
                allocation.setAppliedAmount(new BigDecimal("300.00"));
                request.setAllocations(List.of(allocation));

                // Act: POST /v1/accounting/ap/payments
                mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.paymentRef").value(paymentRef))
                                .andExpect(jsonPath("$.unappliedAmount").value(100.00))
                                .andExpect(jsonPath("$.allocations").isArray())
                                .andExpect(jsonPath("$.allocations[0].vendorBillId")
                                                .value(bill2.getVendorBillId().toString()))
                                .andExpect(jsonPath("$.allocations[0].appliedAmount").value(300.00))
                                .andReturn();

                // Assert: Verify unapplied amount and single allocation
                APPayment savedPayment = apPaymentRepository.findByPaymentRef(paymentRef).orElseThrow();
                assertThat(savedPayment.getUnappliedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
                assertThat(allocationRepository
                                .findByPaymentIdOrderByAllocationSequenceAsc(savedPayment.getPaymentId()))
                                .hasSize(1);
        }

        @Test
        @DisplayName("CP-AP-003: Execute payment is idempotent (same paymentRef returns existing)")
        void testExecutePayment_Idempotency_Success() throws Exception {
                // Arrange: Execute payment once
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                ExecuteAPPaymentRequest request = new ExecuteAPPaymentRequest();
                request.setVendorId(testVendorId);
                request.setGrossAmount(new BigDecimal("200.00"));
                request.setFeeAmount(BigDecimal.ZERO);
                request.setNetAmount(new BigDecimal("200.00"));
                request.setCurrency("USD");
                request.setPaymentRef(paymentRef);
                request.setPaymentMethod(PaymentMethod.CHECK);
                request.setMemo("Idempotency test");
                request.setAllocations(List.of());

                // Act: First request (201 Created)
                MvcResult result1 = mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andReturn();

                String paymentId1 = objectMapper.readTree(result1.getResponse().getContentAsString())
                                .get("paymentId").asString();

                // Act: Second request with same paymentRef and same payload (200 OK, existing
                // payment)
                MvcResult result2 = mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk()) // Idempotent: returns existing
                                .andExpect(jsonPath("$.paymentId").value(paymentId1))
                                .andExpect(jsonPath("$.paymentRef").value(paymentRef))
                                .andReturn();

                String paymentId2 = objectMapper.readTree(result2.getResponse().getContentAsString())
                                .get("paymentId").asString();

                // Assert: Verify same payment instance returned (no duplicate created)
                assertThat(paymentId1).isEqualTo(paymentId2);
                assertThat(apPaymentRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("CP-AP-004: List eligible vendor bills (status=APPROVED, ordered by due date)")
        void testListEligibleBills_Success() throws Exception {
                // Act: GET /v1/accounting/ap/bills?vendorId={vendorId}
                MvcResult result = mockMvc.perform(withAuth(get(API_V1_AP_BILLS))
                                .param("vendorId", testVendorId.toString()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray())
                                .andExpect(jsonPath("$[0].billNumber").value("BILL-001")) // Oldest due first
                                .andExpect(jsonPath("$[0].openAmount").value(500.00))
                                .andExpect(jsonPath("$[1].billNumber").value("BILL-002")) // Newer due second
                                .andExpect(jsonPath("$[1].openAmount").value(300.00))
                                .andReturn();

                // Assert: Verify ordering (oldest due first)
                String responseJson = result.getResponse().getContentAsString();
                assertThat(responseJson).contains("BILL-001").contains("BILL-002");
        }

        @Test
        @DisplayName("CP-AP-005: Get payment by ID")
        void testGetPaymentById_Success() throws Exception {
                // Arrange: Create a payment first
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                APPayment payment = new APPayment();
                payment.setVendorId(testVendorId);
                payment.setPaymentRef(paymentRef);
                payment.setGrossAmount(new BigDecimal("100.00"));
                payment.setFeeAmount(BigDecimal.ZERO);
                payment.setNetAmount(new BigDecimal("100.00"));
                payment.setUnappliedAmount(new BigDecimal("100.00"));
                payment.setCurrency("USD");
                payment.setPaymentMethod(PaymentMethod.ACH);
                payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
                payment.setGatewayTransactionId("TXN-12345");
                payment.setGatewayTimestamp(Instant.now(TEST_CLOCK));
                payment.setCreatedBy("test-user");
                payment = apPaymentRepository.save(payment);

                // Act: GET /v1/accounting/ap/payments/{paymentId}
                mockMvc.perform(withAuth(get(API_V1_AP_PAYMENTS + "/" + payment.getPaymentId())))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId().toString()))
                                .andExpect(jsonPath("$.paymentRef").value(paymentRef))
                                .andExpect(jsonPath("$.grossAmount").value(100.00))
                                .andExpect(jsonPath("$.status").value("GATEWAY_SUCCEEDED"))
                                .andExpect(jsonPath("$.gatewayTransactionId").value("TXN-12345"));
        }

        @Test
        @DisplayName("CP-AP-006: Get payment by paymentRef")
        void testGetPaymentByRef_Success() throws Exception {
                // Arrange: Create a payment first
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                APPayment payment = new APPayment();
                payment.setVendorId(testVendorId);
                payment.setPaymentRef(paymentRef);
                payment.setGrossAmount(new BigDecimal("200.00"));
                payment.setFeeAmount(BigDecimal.ZERO);
                payment.setNetAmount(new BigDecimal("200.00"));
                payment.setUnappliedAmount(BigDecimal.ZERO);
                payment.setCurrency("USD");
                payment.setPaymentMethod(PaymentMethod.CHECK);
                payment.setStatus(APPaymentStatus.GL_POSTED);
                payment.setGatewayTransactionId("TXN-67890");
                payment.setGatewayTimestamp(Instant.now(TEST_CLOCK));
                payment.setGlJournalEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                payment.setGlPostedAt(Instant.now(TEST_CLOCK));
                payment.setCreatedBy("test-user");
                payment = apPaymentRepository.save(payment);

                // Act: GET /v1/accounting/ap/payments/by-ref/{paymentRef}
                mockMvc.perform(withAuth(get(API_V1_AP_PAYMENTS + "/by-ref/" + paymentRef)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.paymentRef").value(paymentRef))
                                .andExpect(jsonPath("$.status").value("GL_POSTED"))
                                .andExpect(jsonPath("$.glJournalEntryId")
                                                .value(payment.getGlJournalEntryId().toString()));
        }

        // ===============================================
        // ERROR SCENARIOS (validation and edge cases)
        // ===============================================

        @Test
        @DisplayName("ERR-AP-001: Execute payment with negative amounts fails")
        void testExecutePayment_NegativeAmount_BadRequest() throws Exception {
                // Arrange: Invalid request with negative grossAmount
                ExecuteAPPaymentRequest request = new ExecuteAPPaymentRequest();
                request.setVendorId(testVendorId);
                request.setGrossAmount(new BigDecimal("-100.00")); // Invalid: negative
                request.setFeeAmount(BigDecimal.ZERO);
                request.setNetAmount(new BigDecimal("-100.00"));
                request.setCurrency("USD");
                request.setPaymentRef(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                request.setPaymentMethod(PaymentMethod.ACH);
                request.setAllocations(List.of());

                // Act & Assert: Expect 400 Bad Request
                mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("ERR-AP-002: Execute payment with allocations exceeding gross amount fails")
        void testExecutePayment_AllocationsExceedGross_BadRequest() throws Exception {
                // Arrange: Invalid request with allocations > grossAmount
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                ExecuteAPPaymentRequest request = new ExecuteAPPaymentRequest();
                request.setVendorId(testVendorId);
                request.setGrossAmount(new BigDecimal("100.00"));
                request.setFeeAmount(BigDecimal.ZERO);
                request.setNetAmount(new BigDecimal("100.00"));
                request.setCurrency("USD");
                request.setPaymentRef(paymentRef);
                request.setPaymentMethod(PaymentMethod.WIRE);

                // Invalid allocation: sum = $600 > gross = $100
                ExecuteAPPaymentRequest.AllocationLineRequest allocation = new ExecuteAPPaymentRequest.AllocationLineRequest();
                allocation.setVendorBillId(bill1.getVendorBillId());
                allocation.setAppliedAmount(new BigDecimal("600.00")); // Exceeds gross
                request.setAllocations(List.of(allocation));

                // Act & Assert: Expect 400 Bad Request (validation error)
                mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("ERR-AP-003: Execute payment with same paymentRef but different payload fails")
        void testExecutePayment_IdempotencyConflict_409() throws Exception {
                // Arrange: Execute payment once
                String paymentRef = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                ExecuteAPPaymentRequest request1 = new ExecuteAPPaymentRequest();
                request1.setVendorId(testVendorId);
                request1.setGrossAmount(new BigDecimal("100.00"));
                request1.setFeeAmount(BigDecimal.ZERO);
                request1.setNetAmount(new BigDecimal("100.00"));
                request1.setCurrency("USD");
                request1.setPaymentRef(paymentRef);
                request1.setPaymentMethod(PaymentMethod.CHECK);
                request1.setAllocations(List.of());

                // Act: First request (201 Created)
                mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request1)))
                                .andExpect(status().isCreated());

                // Arrange: Second request with SAME paymentRef but DIFFERENT payload
                ExecuteAPPaymentRequest request2 = new ExecuteAPPaymentRequest();
                request2.setVendorId(testVendorId);
                request2.setGrossAmount(new BigDecimal("200.00")); // DIFFERENT amount
                request2.setFeeAmount(BigDecimal.ZERO);
                request2.setNetAmount(new BigDecimal("200.00"));
                request2.setCurrency("USD");
                request2.setPaymentRef(paymentRef); // SAME paymentRef
                request2.setPaymentMethod(PaymentMethod.ACH);
                request2.setAllocations(List.of());

                // Act & Assert: Expect 409 Conflict (idempotency key mismatch)
                mockMvc.perform(withAuth(post(API_V1_AP_PAYMENTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request2)))
                                .andDo(print())
                                .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("ERR-AP-004: Get payment by invalid ID returns 404")
        void testGetPaymentById_NotFound() throws Exception {
                // Arrange: Non-existent payment ID
                UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // Act & Assert: Expect 404 Not Found
                mockMvc.perform(withAuth(get(API_V1_AP_PAYMENTS + "/" + nonExistentId)))
                                .andDo(print())
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ERR-AP-005: List bills for non-existent vendor returns empty list")
        void testListEligibleBills_EmptyList() throws Exception {
                // Arrange: Vendor with no bills
                UUID nonExistentVendor = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // Act: GET /v1/accounting/ap/bills?vendorId={nonExistentVendor}
                mockMvc.perform(withAuth(get(API_V1_AP_BILLS))
                                .param("vendorId", nonExistentVendor.toString()))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray())
                                .andExpect(jsonPath("$").isEmpty());
        }
}
