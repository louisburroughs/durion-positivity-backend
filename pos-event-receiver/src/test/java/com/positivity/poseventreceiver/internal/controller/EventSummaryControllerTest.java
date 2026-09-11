package com.positivity.poseventreceiver.internal.controller;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.exception.TenantScopeForbiddenException;
import com.positivity.poseventreceiver.internal.service.EventSummaryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Unit tests for {@link EventSummaryController}, without a Spring context like the other
 * controller tests in this module. The controller hands the optional {@code tenantId} to the
 * service unchanged and translates nothing itself: the service's refusal reaches the platform's
 * global exception handler, which renders the {@code @ResponseStatus} the exception declares.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventSummaryController — tenantId pass-through and the 403 contract")
class EventSummaryControllerTest {

    @Mock
    private EventSummaryService eventSummaryService;

    @InjectMocks
    private EventSummaryController sut;

    @Test
    void passesTheRequestedTenantThroughUnchanged() {
        List<EventSummaryResponse> summary = List.of(new EventSummaryResponse("ORDER_ORDER_CREATE", 3L));
        when(eventSummaryService.getLastHourSummary(TENANT_B)).thenReturn(summary);
        when(eventSummaryService.getLastDaySummary(null)).thenReturn(summary);
        when(eventSummaryService.getLastWeekSummary(TENANT_B)).thenReturn(summary);

        ResponseEntity<List<EventSummaryResponse>> hour = sut.getLastHourSummary(TENANT_B);
        ResponseEntity<List<EventSummaryResponse>> day = sut.getLastDaySummary(null);
        ResponseEntity<List<EventSummaryResponse>> week = sut.getLastWeekSummary(TENANT_B);

        assertThat(hour.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hour.getBody()).isEqualTo(summary);
        assertThat(day.getBody()).isEqualTo(summary);
        assertThat(week.getBody()).isEqualTo(summary);
        verify(eventSummaryService).getLastDaySummary(null);
    }

    @Test
    void letsTheServiceRefusalReachTheGlobalHandlerAsA403() {
        when(eventSummaryService.getLastHourSummary(any())).thenThrow(new TenantScopeForbiddenException());

        assertThatThrownBy(() -> sut.getLastHourSummary(TENANT_B))
                .as("not translated here: the global handler renders the declared status")
                .isInstanceOf(TenantScopeForbiddenException.class);

        ResponseStatus declared =
                AnnotatedElementUtils.findMergedAnnotation(TenantScopeForbiddenException.class, ResponseStatus.class);
        assertThat(declared).isNotNull();
        assertThat(declared.code()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(declared.reason()).isEqualTo(TenantScopeForbiddenException.REASON);
    }
}
