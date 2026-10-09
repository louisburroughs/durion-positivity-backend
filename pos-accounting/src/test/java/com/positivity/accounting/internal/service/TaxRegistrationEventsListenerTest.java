package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtTaxRegistrationRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S32c AC 1 (replicas): accounting's copy of {@code tax.registration.changed} applies each fact once, by
 * registration id and version, keeps the shape-checked number (INTERNAL), and never logs it.
 */
@DisplayName("pos-accounting TaxRegistrationEventsListener — ext_tax_registration (CAP:550 S32c)")
class TaxRegistrationEventsListenerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000f1");
    private static final UUID REGISTRATION_ID = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final String NUMBER = "ZZ12345";

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ExtTaxRegistrationRepository registrations = mock(ExtTaxRegistrationRepository.class);
    private TaxRegistrationEventsListener listener;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TENANT);
        listener = new TaxRegistrationEventsListener(
                CLOCK, new ObjectMapper(), processed, registrations, mock(PlatformTransactionManager.class));
        when(registrations.findById(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a fact whose payload names another tenant than the bound one is skipped, and marked")
    void otherTenantSkipped() {
        TenantContext.clear();
        TenantContext.bind(UUID.fromString("01990000-0000-7000-8000-0000000000f2"));

        listener.onTaxEvent(fact("e-other", 0, null));

        verify(registrations, never()).save(any());
        verify(processed, times(1)).save(any());
    }

    private static String fact(String eventId, long version, String effectiveTo) {
        return """
                {"eventId":"%s","eventType":"tax.registration.changed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-10-08T11:59:00Z",
                 "sourceService":"pos-tax","tenantId":"01990000-0000-7000-8000-0000000000f1",
                 "payload":{"registrationId":"%s","tenantId":"01990000-0000-7000-8000-0000000000f1",
                            "countryCode":"ZZ","regime":"R_1","registrationNumber":"%s","jurisdictionCode":"ZZ",
                            "effectiveFrom":"2026-01-01","effectiveTo":%s,"status":"ACTIVE","version":%d,
                            "changedAt":"2026-10-08T11:59:00Z"}}
                """.formatted(
                        eventId,
                        REGISTRATION_ID,
                        version,
                        REGISTRATION_ID,
                        NUMBER,
                        effectiveTo == null ? "null" : "\"" + effectiveTo + "\"",
                        version);
    }

    @Test
    @DisplayName("a fact writes the copy keyed by the registration, number included, and marks the event")
    void copiesTheFact() {
        listener.onTaxEvent(fact("e-1", 0, null));

        ArgumentCaptor<ExtTaxRegistration> copy = ArgumentCaptor.forClass(ExtTaxRegistration.class);
        verify(registrations).save(copy.capture());
        assertThat(copy.getValue().getRegistrationId()).isEqualTo(REGISTRATION_ID);
        assertThat(copy.getValue().getRegime()).isEqualTo("R_1");
        assertThat(copy.getValue().getJurisdictionCode()).isEqualTo("ZZ");
        assertThat(copy.getValue().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(copy.getValue().getAggregateVersion()).isZero();
        assertThat(copy.getValue().getRegistrationNumber()).isEqualTo(NUMBER);
        assertThat(copy.getValue().getChangedAt()).isEqualTo(Instant.parse("2026-10-08T11:59:00Z"));
        assertThat(copy.getValue().toString()).doesNotContain(NUMBER);
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getOwner()).isEqualTo("tax");
    }

    @Test
    @DisplayName("a redelivered event (the manifest's re-send) is applied once")
    void appliesOnce() {
        when(processed.existsById("e-1")).thenReturn(false, true);

        listener.onTaxEvent(fact("e-1", 0, null));
        listener.onTaxEvent(fact("e-1", 0, null));

        verify(registrations, times(1)).save(any());
    }

    @Test
    @DisplayName("an older version changes nothing; an equal one applies (replay repairs)")
    void versionGuard() {
        ExtTaxRegistration held = ExtTaxRegistration.builder()
                .registrationId(REGISTRATION_ID)
                .countryCode("ZZ")
                .regime("R_1")
                .registrationNumber(NUMBER)
                .jurisdictionCode("ZZ")
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(LocalDate.of(2026, 5, 31))
                .aggregateVersion(2)
                .build();
        when(registrations.findById(REGISTRATION_ID)).thenReturn(Optional.of(held));

        listener.onTaxEvent(fact("e-old", 1, null));
        verify(registrations, never()).save(any());
        assertThat(held.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 5, 31));

        listener.onTaxEvent(fact("e-same", 2, "2026-05-31"));
        verify(registrations, times(1)).save(any());
    }

    @Test
    @DisplayName("an unreadable payload is marked processed and logged without its content")
    void malformedPayload() {
        Logger root = (Logger) LoggerFactory.getLogger("com.positivity");
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        try {
            listener.onTaxEvent(fact("e-bad", 0, null)
                    .replace("\"effectiveFrom\":\"2026-01-01\"", "\"effectiveFrom\":\"not-a-date\""));
            listener.onTaxEvent("not json " + NUMBER);
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }

        verify(registrations, never()).save(any());
        verify(processed, times(1)).save(any());
        assertThat(logs.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(NUMBER));
    }

    @Test
    @DisplayName("a transient database failure propagates for container retry")
    void transientFailurePropagates() {
        when(registrations.findById(any())).thenThrow(new QueryTimeoutException("timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onTaxEvent(fact("e-2", 0, null)));
        verify(processed, never()).save(any());
    }
}
