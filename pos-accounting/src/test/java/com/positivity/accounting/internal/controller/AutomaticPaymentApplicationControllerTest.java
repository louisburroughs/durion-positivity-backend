package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationRow;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.service.AutomaticPaymentApplicationQueryService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Criterion 9 of #2503 through the production security chain and error advice: 200 with display
 * fields, {@code UNDO} only for holders of {@code accounting:payment:reverse}, 400 {@code
 * VALIDATION_ERROR} and 403.
 */
@DisplayName("GET /payment-applications/automatic (#2503)")
@WebMvcTest(AutomaticPaymentApplicationController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class AutomaticPaymentApplicationControllerTest extends BaseControllerSliceTest {

    private static final String APPLY = "accounting:payment:apply";
    private static final String REVERSE = "accounting:payment:reverse";
    private static final String PATH = "/v1/accounting/payment-applications/automatic";
    /** Start of today for {@link #TEST_CLOCK} (2026-09-04T12:00Z). */
    private static final String TODAY = "2026-09-04T00:00:00Z";

    @MockitoBean
    private AutomaticPaymentApplicationQueryService service;

    @Test
    @DisplayName("200 for a holder of accounting:payment:apply: display fields, newest first, default size 50,"
            + " no UNDO without accounting:payment:reverse")
    void listsWithoutUndo() throws Exception {
        when(service.listAutomatic(Instant.parse(TODAY), 0, 50, false)).thenReturn(page(List.of()));

        mockMvc.perform(withAuth(get(PATH).param("since", TODAY), APPLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].invoiceNumber").value("INV-1"))
                .andExpect(jsonPath("$.items[0].customerDisplayName").value("Rivera Trucking"))
                .andExpect(jsonPath("$.items[0].customerReference").value("CUST-00412"))
                .andExpect(jsonPath("$.items[0].source").value("PAYMENT_SETTLED"))
                .andExpect(jsonPath("$.items[0].appliedAmount").value(115.00))
                .andExpect(jsonPath("$.items[0].creditCreatedAmount").value(5.00))
                .andExpect(jsonPath("$.items[0].reversed").value(false))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(service).listAutomatic(Instant.parse(TODAY), 0, 50, false);
    }

    @Test
    @DisplayName("a holder of accounting:payment:reverse is offered UNDO")
    void undoForReverseHolder() throws Exception {
        when(service.listAutomatic(any(), anyInt(), anyInt(), eq(true)))
                .thenReturn(page(List.of(AutomaticPaymentApplicationRow.ACTION_UNDO)));

        mockMvc.perform(withAuth(get(PATH).param("since", TODAY).param("size", "100"), APPLY + "," + REVERSE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actions[0]").value("UNDO"));
        verify(service).listAutomatic(Instant.parse(TODAY), 0, 100, true);
    }

    @Test
    @DisplayName("since 31 days back is accepted; an offset is normalised to an instant")
    void sinceBounds() throws Exception {
        when(service.listAutomatic(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(page(List.of()));

        mockMvc.perform(withAuth(get(PATH).param("since", "2026-08-04T12:00:00Z"), APPLY))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(get(PATH).param("since", "2026-09-04T02:00:00+02:00"), APPLY))
                .andExpect(status().isOk());
        verify(service).listAutomatic(Instant.parse(TODAY), 0, 50, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "",
                "since=",
                "since=yesterday",
                "since=2026-08-04T11:59:59Z",
                "since=" + TODAY + "&size=101",
                "since=" + TODAY + "&size=0",
                "since=" + TODAY + "&page=-1"
            })
    @DisplayName("400 VALIDATION_ERROR: since missing, unparsable or more than 31 days back; size outside 1-100")
    void rejects(String query) throws Exception {
        mockMvc.perform(withAuth(get(PATH + "?" + query), APPLY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).listAutomatic(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("403 without accounting:payment:apply, even for a holder of accounting:payment:reverse")
    void forbidden() throws Exception {
        mockMvc.perform(withAuth(get(PATH).param("since", TODAY), REVERSE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").exists());
        verify(service, never()).listAutomatic(any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("operation id, event id and permission are as the story states")
    void annotations() throws Exception {
        Method method = AutomaticPaymentApplicationController.class.getMethod(
                "listAutomaticPaymentApplications", String.class, int.class, int.class);
        assertThat(method.getAnnotation(Operation.class).operationId()).isEqualTo("listAutomaticPaymentApplications");
        assertThat(method.getAnnotation(EmitEvent.class).id())
                .isEqualTo("ACCOUNTING_PAYMENT_APPLICATION_AUTOMATIC_LIST_VIEW");
        assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(APPLY);
    }

    private static AutomaticPaymentApplicationsPage page(List<String> actions) {
        return AutomaticPaymentApplicationsPage.builder()
                .items(List.of(AutomaticPaymentApplicationRow.builder()
                        .paymentApplicationId(UUID.fromString("0199a000-0000-7000-8000-000000002201"))
                        .paymentId(UUID.fromString("0199a000-0000-7000-8000-000000000101"))
                        .invoiceId(UUID.fromString("0199a000-0000-7000-8000-000000001702"))
                        .source(ApplicationSource.PAYMENT_SETTLED)
                        .appliedAt(Instant.parse("2026-09-04T09:31:07Z"))
                        .appliedAmount(new BigDecimal("115.00"))
                        .currency("USD")
                        .invoiceNumber("INV-1")
                        .customerDisplayName("Rivera Trucking")
                        .customerReference("CUST-00412")
                        .creditCreatedAmount(new BigDecimal("5.00"))
                        .reversed(false)
                        .actions(actions)
                        .build()))
                .page(0)
                .size(50)
                .totalElements(1)
                .totalPages(1)
                .build();
    }
}
