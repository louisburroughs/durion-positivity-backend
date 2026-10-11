package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.SupplierPostgresContainer;
import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierOutboxPublisher;
import com.positivity.supplier.internal.service.SupplierOutboxReplayService;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealOutcome;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRecordView;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRequest;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealResult;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealView;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationDto;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorUpdateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reveal of a vendor's tax-registration number against the real schema (#2621; Security ruling on #2617,
 * ruling 4): the audit row is written in the reveal's transaction, an unreadable number is still recorded,
 * a ciphertext moved to another row does not open, the audit read is tenant-isolated, and no log line
 * carries a number. Every number is obviously fake.
 *
 * <p>{@code @Isolated}: the log capture holds logback's shared loggers, and a Spring context starting in a
 * concurrent class resets them (appenders detached, levels re-applied), which leaves the capture empty.
 */
@Isolated
@Import({
    JpaConfig.class,
    TestClockConfig.class,
    SupplierVendorServiceImpl.class,
    VendorNumberAllocator.class,
    VendorFactPublisher.class,
    SupplierOutboxEventWriter.class,
    SupplierOutboxReplayService.class,
    VendorTaxIdRevealServiceImpl.class,
    VendorTaxIdRevealRecorder.class,
    VendorTaxIdRevealServiceTest.SupportConfig.class
})
@DisplayName("VendorTaxIdRevealService — reveal and its audit (#2621)")
class VendorTaxIdRevealServiceTest extends PostgresSliceTestBase {

    @TestConfiguration
    static class SupportConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    private static final String SSN = "000-00-1234";
    private static final String SSN_BARE = "000001234";
    private static final String REASON = "Verifying W-9 received 2026-10-08";
    private static final UUID OTHER_TENANT = UUID.fromString("01900000-0000-7000-8000-0000000000bb");

    @Autowired
    private SupplierVendorService vendorService;

    @Autowired
    private VendorTaxIdRevealService revealService;

    @Autowired
    private SupplierOutboxReplayService replayService;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @AfterEach
    void clear() throws SQLException {
        SecurityContextHolder.clearContext();
        try (Connection connection = SupplierPostgresContainer.ownerDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM supplier_vendor_tax_id_reveal WHERE tenant_id = '" + OTHER_TENANT + "'");
        }
    }

    private static <T> T as(String user, List<String> roles, Supplier<T> work) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, "n/a", roles.stream().map(SimpleGrantedAuthority::new).toList());
        authentication.setDetails(Map.of("username", user));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static <T> T asController(Supplier<T> work) {
        return as("controller.b", List.of("ROLE_CONTROLLER", "supplier:vendor_tax_id:reveal"), work);
    }

    private VendorView vendorWith(String legalName, String number) {
        return as(
                "clerk.a",
                List.of("ROLE_ACCOUNTING_CLERK"),
                () -> vendorService.createVendor(new VendorCreateRequest(
                        null,
                        legalName,
                        legalName,
                        List.of(new TaxRegistrationDto(null, "SSN", number, null)),
                        null,
                        "NET30",
                        "USD")));
    }

    private List<Map<String, Object>> revealRows(UUID vendorId) {
        entityManager.flush();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT outcome, revealed_by, revealed_by_roles, reason, correlation_id, scheme,"
                        + " registration_id FROM supplier_vendor_tax_id_reveal WHERE vendor_id = ?1")
                .setParameter(1, vendorId)
                .getResultList();
        return rows.stream()
                .map(row -> {
                    Map<String, Object> values = new java.util.HashMap<>();
                    values.put("outcome", row[0]);
                    values.put("revealedBy", row[1]);
                    values.put("roles", row[2]);
                    values.put("reason", row[3]);
                    values.put("correlationId", row[4] == null ? "" : row[4]);
                    values.put("scheme", row[5]);
                    values.put("registrationId", row[6]);
                    return values;
                })
                .toList();
    }

    @Test
    @DisplayName("AC 11: a CONTROLLER with a reason gets the number; exactly one REVEALED row with actor, roles,"
            + " reason and correlation id, and no number or last4")
    void revealRecordsThenReturns() {
        VendorView vendor = vendorWith("Sole One", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();

        TaxIdRevealView revealed = asController(
                        () -> revealService.reveal(vendor.vendorId(), registrationId, new TaxIdRevealRequest(REASON)))
                .view();

        assertThat(revealed.number()).isEqualTo(SSN);
        assertThat(revealed.toString()).as("toString never prints the number").doesNotContain(SSN);
        assertThat(revealRows(vendor.vendorId())).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("REVEALED");
            assertThat(row.get("revealedBy")).isEqualTo("controller.b");
            assertThat(row.get("roles")).isEqualTo("CONTROLLER");
            assertThat(row.get("reason")).isEqualTo(REASON);
            assertThat((String) row.get("correlationId")).isNotBlank();
            assertThat(row.get("scheme")).isEqualTo("SSN");
            String text = row.get("outcome") + "|" + row.get("revealedBy") + "|" + row.get("roles") + "|"
                    + row.get("reason") + "|" + row.get("scheme");
            assertThat(text.contains(SSN) || text.contains(SSN_BARE) || text.contains("1234"))
                    .as("number and last4 absent from the audit row")
                    .isFalse();
        });
    }

    /**
     * AC 11 (Security confirmation on louisburroughs/durion#571): a reason that carries the number itself is
     * refused after the number is decrypted, separators ignored, and leaves exactly one REASON_REJECTED row with
     * no reason. [M] skipping that row fails this test.
     */
    @Test
    @DisplayName("AC 11: a reason containing the number is 400 VALIDATION_ERROR, reveals nothing, and leaves one"
            + " REASON_REJECTED row with a null reason")
    void reasonCarryingTheNumberIsRejected() {
        VendorView vendor = vendorWith("Sole Reasoned", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();

        // Returned, not thrown, so the REASON_REJECTED row commits (ADR-0072 Decision 4, IC-003).
        TaxIdRevealResult refused = asController(() -> revealService.reveal(
                vendor.vendorId(), registrationId, new TaxIdRevealRequest("checking 000-00-1234 per W-9")));
        assertThat(refused.outcome()).isEqualTo(TaxIdRevealOutcome.REASON_REJECTED);
        assertThat(refused.view()).as("nothing revealed").isNull();
        assertThat(revealRows(vendor.vendorId())).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("REASON_REJECTED");
            assertThat(row.get("reason")).isNull();
        });

        // Separators and case do not hide it: the bare digits in a reason match the stored "000-00-1234".
        assertThat(asController(() -> revealService.reveal(
                                vendor.vendorId(), registrationId, new TaxIdRevealRequest("W-9 lists 000001234 again")))
                        .outcome())
                .isEqualTo(TaxIdRevealOutcome.REASON_REJECTED);
        assertThat(revealRows(vendor.vendorId()))
                .extracting(row -> row.get("outcome"))
                .containsOnly("REASON_REJECTED")
                .hasSize(2);
    }

    @Test
    @DisplayName("AC 12: a 9-character reason is 400 JUSTIFICATION_REQUIRED; over 500 is VALIDATION_ERROR")
    void reasonIsRequired() {
        assertThatThrownBy(() -> new TaxIdRevealRequest("too short"))
                .isInstanceOfSatisfying(
                        SupplierValidationException.class,
                        refused -> assertThat(refused.getCode())
                                .isEqualTo(SupplierValidationException.JUSTIFICATION_REQUIRED));
        assertThatThrownBy(() -> new TaxIdRevealRequest("   " + "x".repeat(9) + "   "))
                .isInstanceOfSatisfying(
                        SupplierValidationException.class,
                        refused -> assertThat(refused.getCode())
                                .isEqualTo(SupplierValidationException.JUSTIFICATION_REQUIRED));
        assertThatThrownBy(() -> new TaxIdRevealRequest(null))
                .isInstanceOfSatisfying(
                        SupplierValidationException.class,
                        refused -> assertThat(refused.getCode())
                                .isEqualTo(SupplierValidationException.JUSTIFICATION_REQUIRED));
        // Counted in code points, as PostgreSQL counts them: 9 surrogate pairs are 18 chars but 9 characters.
        String emoji = new String(Character.toChars(0x1F600));
        assertThatThrownBy(() -> new TaxIdRevealRequest(emoji.repeat(9)))
                .isInstanceOfSatisfying(
                        SupplierValidationException.class,
                        refused -> assertThat(refused.getCode())
                                .isEqualTo(SupplierValidationException.JUSTIFICATION_REQUIRED));
        assertThatThrownBy(() -> new TaxIdRevealRequest(emoji.repeat(501)))
                .isInstanceOfSatisfying(
                        SupplierValidationException.class,
                        refused ->
                                assertThat(refused.getCode()).isEqualTo(SupplierValidationException.VALIDATION_ERROR));
        assertThat(new TaxIdRevealRequest(emoji.repeat(500)).reason()).hasSize(1000);
        assertThat(new TaxIdRevealRequest("checking 000-00-1234 per W-9").toString())
                .as("toString never prints the reason")
                .doesNotContain("000-00-1234")
                .contains("<redacted>");
        assertThatThrownBy(() -> new TaxIdRevealRequest("x".repeat(501)))
                .isInstanceOfSatisfying(
                        SupplierValidationException.class,
                        refused ->
                                assertThat(refused.getCode()).isEqualTo(SupplierValidationException.VALIDATION_ERROR));
        assertThat(new TaxIdRevealRequest("  " + "x".repeat(10) + "  ").reason())
                .hasSize(10);
    }

    @Test
    @DisplayName("AC 12: an unknown registration or vendor is 404 and writes no row")
    void unknownIsNotFound() {
        VendorView vendor = vendorWith("Sole Two", SSN);

        assertThatThrownBy(() -> asController(() ->
                        revealService.reveal(vendor.vendorId(), UUID.randomUUID(), new TaxIdRevealRequest(REASON))))
                .isInstanceOfSatisfying(
                        SupplierNotFoundException.class,
                        missing -> assertThat(missing.getCode())
                                .isEqualTo(SupplierNotFoundException.VENDOR_TAX_REGISTRATION_NOT_FOUND));
        assertThatThrownBy(() -> asController(() ->
                        revealService.reveal(UUID.randomUUID(), UUID.randomUUID(), new TaxIdRevealRequest(REASON))))
                .isInstanceOfSatisfying(
                        SupplierNotFoundException.class,
                        missing -> assertThat(missing.getCode()).isEqualTo(SupplierNotFoundException.VENDOR_NOT_FOUND));
        assertThat(revealRows(vendor.vendorId())).isEmpty();
    }

    @Test
    @DisplayName("AC 4: a ciphertext copied into another vendor's registration is 500 UNREADABLE with an"
            + " UNREADABLE row, and no number")
    void movedCiphertextDoesNotOpen() {
        VendorView source = vendorWith("Sole Source", SSN);
        VendorView target = vendorWith("Sole Target", "000-00-5678");
        entityManager.flush();
        entityManager
                .createNativeQuery("UPDATE supplier_vendor t SET tax_registrations = jsonb_set(t.tax_registrations,"
                        + " '{0,numberCiphertext}', s.tax_registrations -> 0 -> 'numberCiphertext')"
                        + " FROM supplier_vendor s WHERE t.vendor_id = ?1 AND s.vendor_id = ?2")
                .setParameter(1, target.vendorId())
                .setParameter(2, source.vendorId())
                .executeUpdate();
        entityManager.clear();
        UUID targetRegistration = target.taxRegistrations().getFirst().registrationId();

        TaxIdRevealResult unreadable = asController(
                () -> revealService.reveal(target.vendorId(), targetRegistration, new TaxIdRevealRequest(REASON)));
        assertThat(unreadable.outcome()).isEqualTo(TaxIdRevealOutcome.UNREADABLE);
        assertThat(unreadable.view()).as("nothing revealed").isNull();
        assertThat(unreadable.failure()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(revealRows(target.vendorId())).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("UNREADABLE");
            assertThat(row.get("reason"))
                    .as("an UNREADABLE row keeps no reason (Security ruling on #2621)")
                    .isNull();
        });
    }

    /** V6's presence CHECK: the reason is NULL exactly on REASON_REJECTED and UNREADABLE rows. */
    @Test
    @DisplayName("V6 refuses a REVEALED row without a reason and an UNREADABLE row with one")
    void reasonPresenceIsEnforcedBySchema() throws SQLException {
        try (Connection connection = SupplierPostgresContainer.ownerDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            for (String[] row : new String[][] {
                {"REVEALED", "NULL"},
                {"UNREADABLE", "'Verifying W-9 received today'"},
                {"REASON_REJECTED", "'Verifying W-9 received today'"}
            }) {
                assertThatThrownBy(() -> statement.execute("INSERT INTO supplier_vendor_tax_id_reveal (tenant_id,"
                                + " reveal_id, vendor_id, registration_id, scheme, revealed_by, revealed_by_roles,"
                                + " reason, revealed_at, outcome) VALUES ('" + OTHER_TENANT + "', gen_random_uuid(),"
                                + " gen_random_uuid(), gen_random_uuid(), 'SSN', 'x', '', " + row[1] + ", now(), '"
                                + row[0] + "')"))
                        .as(row[0] + " with reason " + row[1])
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_svtir_reason_presence");
            }
        }
    }

    /**
     * AC 13, behavioural: when the audit insert itself fails (INSERT revoked from the application role for this
     * test only), the reveal fails and no number is returned.
     */
    @Test
    @DisplayName("AC 13: an audit insert that fails on the real schema returns no number")
    void failedAuditInsertRevealsNothing() throws SQLException {
        VendorView vendor = vendorWith("Sole Closed", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();
        entityManager.flush();
        java.util.concurrent.atomic.AtomicReference<TaxIdRevealResult> returned =
                new java.util.concurrent.atomic.AtomicReference<>();
        grant("REVOKE");
        try {
            assertThatThrownBy(() -> returned.set(asController(() ->
                            revealService.reveal(vendor.vendorId(), registrationId, new TaxIdRevealRequest(REASON)))))
                    .satisfies(failure -> {
                        StringBuilder chain = new StringBuilder();
                        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                            chain.append(cause.getMessage());
                        }
                        assertThat(chain.toString().contains(SSN)
                                        || chain.toString().contains(SSN_BARE))
                                .as("number absent from the failure")
                                .isFalse();
                    });
            assertThat(returned.get()).as("nothing returned").isNull();
        } finally {
            grant("GRANT");
        }
    }

    private static void grant(String verb) throws SQLException {
        try (Connection connection = SupplierPostgresContainer.ownerDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(verb + " INSERT ON supplier_vendor_tax_id_reveal "
                    + ("GRANT".equals(verb) ? "TO" : "FROM") + " " + SupplierPostgresContainer.APP_ROLE);
        }
    }

    @Test
    @DisplayName("AC 14: the audit read lists newest first, and another tenant's rows are invisible (RLS)")
    void auditReadIsNewestFirstAndTenantIsolated() throws SQLException {
        VendorView vendor = vendorWith("Sole Three", SSN);
        UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();
        asController(() -> revealService.reveal(vendor.vendorId(), registrationId, new TaxIdRevealRequest(REASON)));
        asController(() -> revealService.reveal(
                vendor.vendorId(), registrationId, new TaxIdRevealRequest("Second look for the T4A slip")));
        entityManager.flush();
        // Another tenant's row for the same vendor id, written by the owner (RLS-exempt) and committed.
        try (Connection connection = SupplierPostgresContainer.ownerDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO supplier_vendor_tax_id_reveal (tenant_id, reveal_id, vendor_id,"
                    + " registration_id, scheme, revealed_by, revealed_by_roles, reason, correlation_id, revealed_at,"
                    + " outcome) VALUES ('" + OTHER_TENANT + "', gen_random_uuid(), '" + vendor.vendorId() + "', '"
                    + registrationId + "', 'SSN', 'intruder', 'ADMIN', 'Another tenant entirely', 'c-x', now() +"
                    + " interval '1 day', 'REVEALED')");
        }

        List<TaxIdRevealRecordView> page =
                revealService.listReveals(vendor.vendorId(), 0, 20).items();

        assertThat(page).hasSize(2);
        assertThat(page).extracting(TaxIdRevealRecordView::revealedBy).containsOnly("controller.b");
        assertThat(page.getFirst().revealedAt()).isAfterOrEqualTo(page.get(1).revealedAt());
        assertThat(page).extracting(TaxIdRevealRecordView::outcome).containsOnly(TaxIdRevealOutcome.REVEALED);
        assertThat(page.getFirst().revealedByRoles()).containsExactly("CONTROLLER");
        assertThatThrownBy(() -> revealService.listReveals(vendor.vendorId(), 0, 201))
                .isInstanceOf(SupplierValidationException.class);
    }

    /**
     * AC 17: a ListAppender at DEBUG over {@code com.positivity.supplier} and {@code com.positivity.domainevents}
     * across create, update, reveal, both replays and an outbox publish (one success, one broker failure) never
     * holds the number or its last four as a tax-registration value. Failure messages never print the lines.
     */
    @Test
    @DisplayName("AC 17: no log line at any level carries a registration number or last4")
    void noNumberInAnyLogLine() {
        Logger supplier = (Logger) org.slf4j.LoggerFactory.getLogger("com.positivity.supplier");
        Logger events = (Logger) org.slf4j.LoggerFactory.getLogger("com.positivity.domainevents");
        Level supplierLevel = supplier.getLevel();
        Level eventsLevel = events.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        supplier.addAppender(appender);
        events.addAppender(appender);
        supplier.setLevel(Level.DEBUG);
        events.setLevel(Level.DEBUG);
        try {
            Instant start = Instant.now(clock).minusSeconds(5);
            VendorView vendor = vendorWith("Sole Logged", SSN);
            UUID registrationId = vendor.taxRegistrations().getFirst().registrationId();
            VendorView updated = as(
                    "clerk.a",
                    List.of("ROLE_ACCOUNTING_CLERK"),
                    () -> vendorService.updateVendor(
                            vendor.vendorId(),
                            new VendorUpdateRequest(
                                    vendor.legalName(),
                                    vendor.displayName(),
                                    List.of(new TaxRegistrationDto(registrationId, "SSN", SSN, null)),
                                    "NET30",
                                    "USD",
                                    vendor.version())));
            asController(
                    () -> revealService.reveal(updated.vendorId(), registrationId, new TaxIdRevealRequest(REASON)));
            assertThat(asController(() -> revealService.reveal(
                                    updated.vendorId(), registrationId, new TaxIdRevealRequest("per W-9 " + SSN)))
                            .outcome())
                    .isEqualTo(TaxIdRevealOutcome.REASON_REJECTED);
            assertThatThrownBy(() -> as(
                            "clerk.a",
                            List.of(),
                            () -> vendorService.updateVendor(
                                    updated.vendorId(),
                                    new VendorUpdateRequest(
                                            vendor.legalName(),
                                            vendor.displayName(),
                                            List.of(new TaxRegistrationDto(null, "EIN123", "000-00-4321", "US-123")),
                                            "NET30",
                                            "USD",
                                            updated.version()))))
                    .isInstanceOf(SupplierValidationException.class);
            as("admin", List.of("ROLE_ADMIN"), () -> vendorService.replayFacts(null, 1000));
            publishPending(true);
            replayService.replayEventsBetween(start, Instant.now(clock).plusSeconds(5));
            publishPending(false);
            assertThatThrownBy(() -> as(
                            "clerk.a",
                            List.of(),
                            () -> vendorService.updateVendor(
                                    updated.vendorId(),
                                    new VendorUpdateRequest(
                                            vendor.legalName(),
                                            vendor.displayName(),
                                            List.of(new TaxRegistrationDto(null, "SSN", null, null)),
                                            "NET30",
                                            "USD",
                                            updated.version()))))
                    .isInstanceOf(SupplierValidationException.class);
        } finally {
            supplier.detachAppender(appender);
            events.detachAppender(appender);
            supplier.setLevel(supplierLevel);
            events.setLevel(eventsLevel);
        }

        assertThat(appender.list).as("the capture saw the flow").isNotEmpty();
        long leaking = appender.list.stream()
                .filter(event -> {
                    String text = event.getFormattedMessage()
                            + (event.getThrowableProxy() == null
                                    ? ""
                                    : event.getThrowableProxy().getMessage());
                    return text.contains(SSN)
                            || text.contains(SSN_BARE)
                            || text.contains("EIN123")
                            || text.contains("US-123")
                            || text.contains("000-00-4321")
                            || text.contains("per W-9")
                            || text.contains("last4")
                            || text.contains("numberCiphertext");
                })
                .count();
        // A count, never the lines: printing them would put the value in the build log.
        assertThat(leaking)
                .as("log lines carrying a registration number or last4")
                .isZero();
    }

    /** Drains the outbox through the real publisher against a stub broker that accepts, or refuses. */
    @SuppressWarnings("unchecked")
    private void publishPending(boolean brokerAccepts) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(
                        brokerAccepts
                                ? CompletableFuture.completedFuture(null)
                                : CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noRegistry = mock(ObjectProvider.class);
        new SupplierOutboxPublisher(outboxRepository, kafka, clock, noRegistry).publishPending();
    }
}
