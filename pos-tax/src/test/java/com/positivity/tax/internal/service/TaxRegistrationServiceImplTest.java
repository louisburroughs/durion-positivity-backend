package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.tax.TaxRegistrationChangedV1;
import com.positivity.tax.internal.config.OutboxEventWriter;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.dto.TaxRegistrationUpdateRequest;
import com.positivity.tax.internal.entity.TaxRegistration;
import com.positivity.tax.internal.entity.TaxRegistrationHistory;
import com.positivity.tax.internal.exception.TaxRegistrationConflictException;
import com.positivity.tax.internal.exception.TaxRegistrationNotFoundException;
import com.positivity.tax.internal.exception.TaxRequestInvalidException;
import com.positivity.tax.internal.exception.TaxRequestUnprocessableException;
import com.positivity.tax.internal.repository.TaxRegistrationHistoryRepository;
import com.positivity.tax.internal.repository.TaxRegistrationRepository;
import com.positivity.tenancy.TenantContext;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP:550 S32c: the registry's rules, against the S32a test-only {@code ZZ} profile (AC 6) and the first country's
 * placeholder fixtures. Not tax law.
 */
@DisplayName("TaxRegistrationServiceImpl — the tenant tax-registration registry (CAP:550 S32c)")
class TaxRegistrationServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000f1");
    private static final String ACTOR = "01990000-0000-7000-8000-0000000000e1";
    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final String ZZ_NUMBER = "zz-12345";
    private static final String MALFORMED = "ZZ1234X";

    private final TaxRegistrationRepository registrations = mock(TaxRegistrationRepository.class);
    private final TaxRegistrationHistoryRepository history = mock(TaxRegistrationHistoryRepository.class);
    private final OutboxEventWriter outbox = mock(OutboxEventWriter.class);
    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();

    private TaxRegistrationServiceImpl service(
            java.util.Map<String, String> country, java.util.Map<String, String> stubs) {
        TaxProperties properties = TaxProfileFixtures.bind(country, stubs);
        TaxCountryProfiles profiles = new TaxCountryProfiles(properties);
        RegistrationNumberShapes shapes = new RegistrationNumberShapes(properties, profiles);
        return new TaxRegistrationServiceImpl(
                registrations, history, profiles, shapes, outbox, objectMapper, CLOCK, "tax.events.v1");
    }

    private TaxRegistrationServiceImpl zz() {
        return service(TaxProfileFixtures.MADE_UP_COUNTRY, TaxProfileFixtures.MADE_UP_COUNTRY_STUBS);
    }

    @BeforeEach
    void bind() {
        TenantContext.bind(TENANT);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR, null, List.of()));
        when(history.findByRequestId(any())).thenReturn(Optional.empty());
        when(registrations.findByCountryCodeAndRegime(anyString(), anyString())).thenReturn(List.of());
        when(registrations.saveAndFlush(any())).thenAnswer(invocation -> {
            TaxRegistration saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.fromString("01990000-0000-7000-8000-0000000000a1"));
                saved.setCreatedAt(CLOCK.instant());
            }
            saved.setUpdatedAt(CLOCK.instant());
            return saved;
        });
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static TaxRegistrationCreateRequest create(String country, String regime, String number, LocalDate from) {
        return new TaxRegistrationCreateRequest(
                country, regime, number, from, null, "Registered with the authority", UUID.randomUUID());
    }

    @Test
    @DisplayName("AC 6: a regime of the test-only ZZ profile is accepted with no code change; the jurisdiction is"
            + " derived from its configuration and the fact is queued once")
    void zzRegimeFromConfiguration() {
        TaxRegistrationService.WriteResult result = zz().create(create("ZZ", "R_1", ZZ_NUMBER, JAN_1));

        assertThat(result.replayed()).isFalse();
        assertThat(result.registration().registrationNumber())
                .as("stored normalised")
                .isEqualTo("ZZ12345");
        assertThat(result.registration().jurisdictionCode())
                .as("a regime listing no region covers the country")
                .isEqualTo("ZZ");
        assertThat(result.registration().status()).isEqualTo("ACTIVE");
        assertThat(result.registration().createdBy()).isEqualTo(ACTOR);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<DomainEventEnvelope<?>> envelope = ArgumentCaptor.forClass((Class) DomainEventEnvelope.class);
        verify(outbox, times(1)).publish(eq("tax.events.v1"), envelope.capture());
        TaxRegistrationChangedV1 fact =
                (TaxRegistrationChangedV1) envelope.getValue().payload();
        assertThat(envelope.getValue().eventType()).isEqualTo(TaxRegistrationChangedV1.EVENT_TYPE);
        assertThat(fact.tenantId()).isEqualTo(TENANT);
        assertThat(fact.countryCode()).isEqualTo("ZZ");
        assertThat(fact.regime()).isEqualTo("R_1");
        assertThat(fact.registrationNumber()).isEqualTo("ZZ12345");
        assertThat(fact.jurisdictionCode()).isEqualTo("ZZ");
        assertThat(fact.status()).isEqualTo("ACTIVE");

        ArgumentCaptor<TaxRegistrationHistory> change = ArgumentCaptor.forClass(TaxRegistrationHistory.class);
        verify(history).saveAndFlush(change.capture());
        assertThat(change.getValue().getChangeType()).isEqualTo(TaxRegistrationHistory.CREATE);
        assertThat(change.getValue().getActor()).as("AC 3: the forwarded actor").isEqualTo(ACTOR);
        assertThat(change.getValue().getOldState()).isNull();
    }

    @Test
    @DisplayName("a regime listing exactly one region takes that region as its jurisdiction")
    void singleRegionJurisdiction() {
        TaxRegistrationServiceImpl firstCountry =
                service(TaxProfileFixtures.FIRST_COUNTRY, TaxProfileFixtures.FIRST_COUNTRY_STUBS);

        TaxRegistrationService.WriteResult result =
                firstCountry.create(create("CA", "QST", "1234567890 TQ 0001", JAN_1));

        assertThat(result.registration().jurisdictionCode()).isEqualTo("QC");
        assertThat(result.registration().registrationNumber()).isEqualTo("1234567890TQ0001");
    }

    @Test
    @DisplayName("AC 2: a malformed number is 400 on registrationNumber; nothing is stored or queued, and the value"
            + " is in neither the error nor the logs")
    void malformedNumberRefused() {
        Logger root = (Logger) LoggerFactory.getLogger("com.positivity");
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> zz().create(create("ZZ", "R_1", MALFORMED, JAN_1)))
                    .isInstanceOfSatisfying(TaxRequestInvalidException.class, refused -> {
                        assertThat(refused.getFieldErrors()).singleElement().satisfies(error -> {
                            assertThat(error.field()).isEqualTo("registrationNumber");
                            assertThat(error.message()).doesNotContain(MALFORMED);
                        });
                        assertThat(refused.getMessage()).doesNotContain(MALFORMED);
                    });
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }

        verify(registrations, never()).saveAndFlush(any());
        verify(history, never()).saveAndFlush(any());
        verify(outbox, never()).publish(anyString(), any());
        assertThat(logs.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(MALFORMED));
    }

    @Test
    @DisplayName("ADR-0017: a regime the country does not declare is 422 TAX_REGIME_NOT_DECLARED, a country without a"
            + " profile 422 TAX_JURISDICTION_NOT_CONFIGURED")
    void undeclaredRegimeOrCountry() {
        assertThatThrownBy(() -> zz().create(create("ZZ", "R_2", ZZ_NUMBER, JAN_1)))
                .isInstanceOfSatisfying(TaxRequestUnprocessableException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("TAX_REGIME_NOT_DECLARED");
                    assertThat(refused.getFieldErrors())
                            .extracting(error -> error.field())
                            .containsExactly("regime");
                });
        assertThatThrownBy(() -> zz().create(create("XY", "R_1", ZZ_NUMBER, JAN_1)))
                .isInstanceOfSatisfying(TaxRequestUnprocessableException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("TAX_JURISDICTION_NOT_CONFIGURED");
                    assertThat(refused.getFieldErrors())
                            .extracting(error -> error.field())
                            .containsExactly("countryCode");
                });
        verify(registrations, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a missing field, a short justification or an end before the start is 400, naming each field")
    void shapeOfTheRequest() {
        TaxRegistrationCreateRequest bad =
                new TaxRegistrationCreateRequest("zz", " ", null, JAN_1, JAN_1.minusDays(1), "too short", null);

        assertThatThrownBy(() -> zz().create(bad))
                .isInstanceOfSatisfying(
                        TaxRequestInvalidException.class,
                        refused -> assertThat(refused.getFieldErrors())
                                .extracting(error -> error.field())
                                .containsExactly(
                                        "countryCode",
                                        "regime",
                                        "registrationNumber",
                                        "effectiveTo",
                                        "justification",
                                        "requestId"));
    }

    @Nested
    @DisplayName("overlap")
    class Overlap {

        private TaxRegistration existing(LocalDate from, LocalDate to) {
            return TaxRegistration.builder()
                    .id(UUID.fromString("01990000-0000-7000-8000-0000000000a9"))
                    .countryCode("ZZ")
                    .regime("R_1")
                    .registrationNumber("ZZ99999")
                    .jurisdictionCode("ZZ")
                    .effectiveFrom(from)
                    .effectiveTo(to)
                    .build();
        }

        @Test
        @DisplayName("[M] AC 1: a registration in effect on a date the new one covers is 409 TAX_REGISTRATION_OVERLAP")
        void overlapRefused() {
            when(registrations.findByCountryCodeAndRegime("ZZ", "R_1")).thenReturn(List.of(existing(JAN_1, null)));

            assertThatThrownBy(() -> zz().create(create("ZZ", "R_1", ZZ_NUMBER, LocalDate.of(2026, 6, 1))))
                    .isInstanceOfSatisfying(
                            TaxRegistrationConflictException.class,
                            conflict -> assertThat(conflict.getCode()).isEqualTo("TAX_REGISTRATION_OVERLAP"));
            verify(registrations, never()).saveAndFlush(any());
            verify(outbox, never()).publish(anyString(), any());
        }

        @Test
        @DisplayName("both ends are inclusive: a registration ended the day before the new start does not overlap")
        void adjacentRegistrationsDoNotOverlap() {
            when(registrations.findByCountryCodeAndRegime("ZZ", "R_1"))
                    .thenReturn(List.of(existing(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))));

            assertThat(zz().create(create("ZZ", "R_1", ZZ_NUMBER, JAN_1)).replayed())
                    .isFalse();
            assertThat(TaxRegistrationServiceImpl.overlaps(JAN_1, null, LocalDate.of(2025, 1, 1), JAN_1))
                    .as("sharing the last day overlaps")
                    .isTrue();
        }

        @Test
        @DisplayName("two concurrent writes stopped by the exclusion constraint answer the same 409")
        void exclusionViolationIsTheSameConflict() {
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                            "no overlap", new SQLException("conflicting key value", "23P01")))
                    .when(registrations)
                    .saveAndFlush(any());

            assertThatThrownBy(() -> zz().create(create("ZZ", "R_1", ZZ_NUMBER, JAN_1)))
                    .isInstanceOfSatisfying(
                            TaxRegistrationConflictException.class,
                            conflict -> assertThat(conflict.getCode()).isEqualTo("TAX_REGISTRATION_OVERLAP"));
        }
    }

    /** Creates once with {@code requestId} and makes the history mock answer it, as the table would. */
    private TaxRegistrationHistory createdWith(TaxRegistrationServiceImpl service, UUID requestId) {
        service.create(new TaxRegistrationCreateRequest(
                "ZZ", "R_1", ZZ_NUMBER, JAN_1, null, "Registered with the authority", requestId));
        ArgumentCaptor<TaxRegistrationHistory> change = ArgumentCaptor.forClass(TaxRegistrationHistory.class);
        verify(history).saveAndFlush(change.capture());
        when(history.findByRequestId(requestId)).thenReturn(Optional.of(change.getValue()));
        return change.getValue();
    }

    @Nested
    @DisplayName("replay (ADR-0017 §2)")
    class Replay {

        @Test
        @DisplayName("[M] AC 1: the same request again returns the first result and writes nothing more")
        void sameRequestReturnsTheFirstResult() {
            TaxRegistrationServiceImpl service = zz();
            UUID requestId = UUID.randomUUID();
            createdWith(service, requestId);

            TaxRegistrationService.WriteResult result = service.create(new TaxRegistrationCreateRequest(
                    "ZZ", "R_1", "zz 12345", JAN_1, null, "Registered with the authority, again", requestId));

            assertThat(result.replayed()).isTrue();
            assertThat(result.registration().registrationId())
                    .isEqualTo(UUID.fromString("01990000-0000-7000-8000-0000000000a1"));
            assertThat(result.registration().registrationNumber()).isEqualTo("ZZ12345");
            verify(registrations, times(1)).saveAndFlush(any());
            verify(history, times(1)).saveAndFlush(any());
            verify(outbox, times(1)).publish(anyString(), any());
        }

        @Test
        @DisplayName("[M] the same requestId with another number or other dates is 409 IDEMPOTENCY_CONFLICT, never"
                + " echoing the number")
        void sameRequestIdOtherBody() {
            TaxRegistrationServiceImpl service = zz();
            UUID requestId = UUID.randomUUID();
            createdWith(service, requestId);

            for (TaxRegistrationCreateRequest other : List.of(
                    new TaxRegistrationCreateRequest(
                            "ZZ", "R_1", "ZZ54321", JAN_1, null, "Registered with the authority", requestId),
                    new TaxRegistrationCreateRequest(
                            "ZZ",
                            "R_1",
                            ZZ_NUMBER,
                            JAN_1.plusDays(1),
                            null,
                            "Registered with the authority",
                            requestId),
                    new TaxRegistrationCreateRequest(
                            "ZZ",
                            "R_1",
                            ZZ_NUMBER,
                            JAN_1,
                            LocalDate.of(2026, 12, 31),
                            "Registered with the authority",
                            requestId))) {
                assertThatThrownBy(() -> service.create(other))
                        .isInstanceOfSatisfying(TaxRegistrationConflictException.class, conflict -> {
                            assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                            assertThat(conflict.getMessage())
                                    .doesNotContain("54321")
                                    .doesNotContain("12345");
                        });
            }
            verify(registrations, times(1)).saveAndFlush(any());
        }

        @Test
        @DisplayName("[M] a create's requestId reused on PUT /{other} is 409 IDEMPOTENCY_CONFLICT")
        void createRequestIdReusedOnAChange() {
            TaxRegistrationServiceImpl service = zz();
            UUID requestId = UUID.randomUUID();
            createdWith(service, requestId);
            UUID other = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
            when(registrations.findById(other))
                    .thenReturn(Optional.of(TaxRegistration.builder()
                            .id(other)
                            .countryCode("ZZ")
                            .regime("R_1")
                            .registrationNumber(ZZ_NUMBER)
                            .jurisdictionCode("ZZ")
                            .effectiveFrom(JAN_1)
                            .build()));

            assertThatThrownBy(() -> service.update(
                            other,
                            new TaxRegistrationUpdateRequest(
                                    "ZZ12345", JAN_1, null, 0L, "Registered with the authority", requestId)))
                    .isInstanceOfSatisfying(
                            TaxRegistrationConflictException.class,
                            conflict -> assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        }

        @Test
        @DisplayName("a concurrent request with the same id, stopped by the unique key, is 409 IDEMPOTENCY_CONFLICT")
        void concurrentDuplicate() {
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                            "duplicate", new SQLException("duplicate key value", "23505")))
                    .when(history)
                    .saveAndFlush(any());

            assertThatThrownBy(() -> zz().create(create("ZZ", "R_1", ZZ_NUMBER, JAN_1)))
                    .isInstanceOfSatisfying(
                            TaxRegistrationConflictException.class,
                            conflict -> assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        private final UUID id = UUID.fromString("01990000-0000-7000-8000-0000000000a1");

        private TaxRegistration stored(long version) {
            return TaxRegistration.builder()
                    .id(id)
                    .countryCode("ZZ")
                    .regime("R_1")
                    .registrationNumber("ZZ12345")
                    .jurisdictionCode("ZZ")
                    .effectiveFrom(JAN_1)
                    .version(version)
                    .createdAt(CLOCK.instant())
                    .createdBy(ACTOR)
                    .updatedAt(CLOCK.instant())
                    .updatedBy(ACTOR)
                    .build();
        }

        private TaxRegistrationUpdateRequest end(long version) {
            return new TaxRegistrationUpdateRequest(
                    "ZZ12345", JAN_1, LocalDate.of(2026, 9, 30), version, "Deregistered on Sept 30", UUID.randomUUID());
        }

        @Test
        @DisplayName("ending a registration excludes itself from the overlap check, records old and new, and queues"
                + " one fact")
        void endsTheRegistration() {
            TaxRegistration current = stored(2);
            when(registrations.findById(id)).thenReturn(Optional.of(current));
            when(registrations.findByCountryCodeAndRegime("ZZ", "R_1")).thenReturn(List.of(current));

            TaxRegistrationService.WriteResult result = zz().update(id, end(2));

            assertThat(result.registration().effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(result.registration().status()).isEqualTo("ENDED");
            ArgumentCaptor<TaxRegistrationHistory> change = ArgumentCaptor.forClass(TaxRegistrationHistory.class);
            verify(history).saveAndFlush(change.capture());
            assertThat(change.getValue().getChangeType()).isEqualTo(TaxRegistrationHistory.UPDATE);
            assertThat(change.getValue().getOldState()).contains("\"effectiveTo\":null");
            assertThat(change.getValue().getNewState()).contains("2026-09-30");
            verify(outbox, times(1)).publish(eq("tax.events.v1"), any());
        }

        @Test
        @DisplayName("a stale version is 409 OPTIMISTIC_LOCK and nothing changes")
        void staleVersion() {
            when(registrations.findById(id)).thenReturn(Optional.of(stored(3)));

            assertThatThrownBy(() -> zz().update(id, end(2)))
                    .isInstanceOfSatisfying(
                            TaxRegistrationConflictException.class,
                            conflict -> assertThat(conflict.getCode()).isEqualTo("OPTIMISTIC_LOCK"));
            verify(registrations, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an unknown registration is 404")
        void unknown() {
            when(registrations.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> zz().update(id, end(0))).isInstanceOf(TaxRegistrationNotFoundException.class);
        }
    }
}
