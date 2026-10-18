package com.positivity.accounting.internal.billintake.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.billintake.dto.BillIntakeItemResponse;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.service.BillIntakeEntry;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@DisplayName("BillIntakeController: POST /v1/accounting/bill-intake/uploads (S25 #2518)")
@WebMvcTest(BillIntakeController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class BillIntakeControllerTest extends BaseControllerSliceTest {

    private static final String URL = "/v1/accounting/bill-intake/uploads";
    private static final String REQUEST_ID = "019a0000-0000-7000-8000-000000000001";

    @MockitoBean
    private BillIntakeEntry entry;

    private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(
            String authorities) {
        return multipart(URL)
                .file(pdf())
                .param("requestId", REQUEST_ID)
                .header("X-User", TEST_USER)
                .header("X-Authorities", authorities);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("files", "bill.pdf", "application/pdf", "%PDF-1.7 a bill".getBytes());
    }

    @Test
    @DisplayName("201 with one item per file for a caller holding accounting:bill_intake:create")
    void uploads() throws Exception {
        UUID id = UUID.fromString("019a0000-0000-7000-8000-0000000000aa");
        when(entry.receive(any(), eq(BillIntakeChannel.UPLOAD), eq(UUID.fromString(REQUEST_ID)), any()))
                .thenReturn(new BillIntakeEntry.Received(
                        List.of(new BillIntakeItemResponse(
                                id,
                                "BI-202610-0000001",
                                BillIntakeChannel.UPLOAD,
                                BillIntakeStatus.QUARANTINED,
                                null,
                                "abc",
                                Instant.parse("2026-10-17T09:00:00Z"))),
                        false));

        mockMvc.perform(upload("accounting:bill_intake:create"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].intakeReference").value("BI-202610-0000001"))
                .andExpect(jsonPath("$.items[0].status").value("QUARANTINED"));
        verify(entry).receive(any(), eq(BillIntakeChannel.UPLOAD), eq(UUID.fromString(REQUEST_ID)), any());
    }

    @Test
    @DisplayName("403 for a caller without the permission, and the entry is never called")
    void forbidden() throws Exception {
        mockMvc.perform(upload("accounting:ap:view"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(entry);
    }

    @Test
    @DisplayName("409 IDEMPOTENCY_CONFLICT when the request id was used with other files")
    void conflict() throws Exception {
        when(entry.receive(any(), any(), any(), any()))
                .thenThrow(new VendorBillException(
                        VendorBillException.Code.IDEMPOTENCY_CONFLICT, "The requestId was used before with other files"));

        mockMvc.perform(upload("accounting:bill_intake:create"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("413 PAYLOAD_TOO_LARGE for a file over the limit, whether the service or the multipart layer refuses it")
    void tooLarge() throws Exception {
        when(entry.receive(any(), any(), any(), any()))
                .thenThrow(new VendorBillException(VendorBillException.Code.PAYLOAD_TOO_LARGE, "A file is over the limit"));
        mockMvc.perform(upload("accounting:bill_intake:create"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        when(entry.receive(any(), any(), any(), any())).thenThrow(new MaxUploadSizeExceededException(100L));
        mockMvc.perform(upload("accounting:bill_intake:create"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
}
