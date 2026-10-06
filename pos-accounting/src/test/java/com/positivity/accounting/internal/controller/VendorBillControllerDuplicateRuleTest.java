package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.service.VendorBillService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The 409 {@code AP_BILL_DUPLICATE} envelope of #2501 (ADR-0070 Decision 4; ADR-0017; ADR-0064), on
 * the two REST paths that write a bill number, through the production {@code
 * AccountingExceptionHandler}. Before this mapping a duplicate could only surface as the generic
 * {@code DUPLICATE_RESOURCE} of pos-web-common, which names no original.
 */
@DisplayName("VendorBillController — 409 AP_BILL_DUPLICATE (#2501)")
@WebMvcTest(VendorBillController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class VendorBillControllerDuplicateRuleTest extends BaseControllerSliceTest {

    private static final UUID ORIGINAL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String GOODS_RECEIVED = """
            {"eventId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
             "organizationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
             "purchaseOrderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d",
             "vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e",
             "vendorName":"Acme Tire",
             "receivedDate":"2026-10-01T09:30:00",
             "lineItems":[
               {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f",
                "description":"Brake pads",
                "quantity":10,
                "unitPrice":24.99,
                "isInventoryItem":true}]}
            """;

    private static final String VENDOR_INVOICE = """
            {"eventId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
             "organizationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
             "vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e",
             "invoiceReference":"inv 00123",
             "invoiceDate":"2026-10-01T00:00:00",
             "dueDate":"2026-10-31T00:00:00",
             "lineItems":[
               {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f",
                "description":"Brake pads",
                "quantity":10,
                "unitPrice":24.99}]}
            """;

    @MockitoBean
    private VendorBillService vendorBillService;

    private static VendorBillDuplicateException duplicateOf(String vendorName) {
        VendorBill original = new VendorBill(ORIGINAL_ID);
        original.setBillNumber("INV-00123");
        original.setVendorName(vendorName);
        original.setBillDate(LocalDateTime.of(2026, 10, 1, 9, 30));
        original.setStatus(VendorBillStatus.APPROVED);
        return new VendorBillDuplicateException(original);
    }

    @Test
    @DisplayName(
            "criterion 4: POST /vendor-bills answers 409 AP_BILL_DUPLICATE naming the original, with its id as referenceId")
    void createAnswers409WithTheOriginal() throws Exception {
        when(vendorBillService.handleGoodsReceivedEvent(any())).thenThrow(duplicateOf("Acme Tire"));

        String body = mockMvc.perform(withAuth(post("/v1/accounting/vendor-bills"), "accounting:ap:pay")
                        .header("X-Correlation-Id", "corr-2501")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GOODS_RECEIVED))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id", "corr-2501"))
                .andExpect(jsonPath("$.code").value("AP_BILL_DUPLICATE"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("Bill INV-00123 from Acme Tire dated 2026-10-01 already exists (APPROVED)"))
                .andExpect(jsonPath("$.referenceId").value(ORIGINAL_ID.toString()))
                .andExpect(jsonPath("$.nextAction").value("Open the existing bill."))
                .andExpect(jsonPath("$.correlationId").value("corr-2501"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-04T12:00:00Z"))
                .andExpect(jsonPath("$.supportAction").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // ADR-0064: the id travels as referenceId only; the sentence a person reads carries none.
        String sentence = JsonPath.read(body, "$.message");
        assertThat(UUID_PATTERN.matcher(sentence).find()).isFalse();
    }

    @Test
    @DisplayName(
            "criterion 6: POST /vendor-bills/match answers the same 409, and says this vendor when the bill has no vendor name")
    void matchAnswers409WithTheOriginal() throws Exception {
        when(vendorBillService.handleVendorInvoiceReceivedEvent(any())).thenThrow(duplicateOf(null));

        mockMvc.perform(withAuth(post("/v1/accounting/vendor-bills/match"), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VENDOR_INVOICE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AP_BILL_DUPLICATE"))
                .andExpect(jsonPath("$.message")
                        .value("Bill INV-00123 from this vendor dated 2026-10-01 already exists (APPROVED)"))
                .andExpect(jsonPath("$.referenceId").value(ORIGINAL_ID.toString()))
                .andExpect(jsonPath("$.nextAction").value("Open the existing bill."));
    }
}
