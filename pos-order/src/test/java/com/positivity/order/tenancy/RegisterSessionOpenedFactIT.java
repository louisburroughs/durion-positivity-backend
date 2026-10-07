package com.positivity.order.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.order.RegisterSessionOpenedV1;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.OutboxEvent;
import com.positivity.order.internal.exception.RegisterFloatLocationMismatchException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.OutboxEventRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.service.RegisterSessionFactsBootstrap;
import com.positivity.order.internal.service.RegisterSessionService;
import com.positivity.order.internal.service.model.OpenSessionCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S40 (#2578) on Postgres: an open queues exactly one {@code order.session.opened} on the transactional
 * outbox in its own transaction (AC1), a refused open queues none (AC2), and the start republish queues one per
 * OPEN or CLOSING session of each tenant and none for a CLOSED one (AC3). Same context as {@link DrawerMovementsIT},
 * whose configuration wires in the Kafka-rails outbox writer the broker-less {@code pg} profile leaves out.
 */
@DisplayName("order.session.opened on Postgres (CAP:550 S40)")
@Import(DrawerMovementsIT.OutboxWriterConfig.class)
class RegisterSessionOpenedFactIT extends PostgresTenancyTestBase {

    private static final String OPENED = "\"" + RegisterSessionOpenedV1.EVENT_TYPE + "\"";

    @Autowired
    private RegisterSessionService registerSessionService;

    @Autowired
    private RegisterSessionRepository sessions;

    @Autowired
    private ExtAccountingRegisterFloatRepository floats;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OrderDomainEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void signIn() {
        var token = new UsernamePasswordAuthenticationToken("cashier-it", "n/a", List.of());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "cashier-it",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                LocationScope.unscoped()));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC1: an open queues exactly one order.session.opened, keyed on the session at version 0")
    void openQueuesOneFact() {
        String register = "T-" + UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        UUID sessionId = asTenant(TENANT_A, () -> {
            floats.saveAndFlush(floatCopy(register, shop));
            return registerSessionService
                    .openSession(new OpenSessionCommand(register, null))
                    .sessionId();
        });

        List<OutboxEvent> facts = openedFacts(sessionId);
        assertThat(facts).hasSize(1);
        OutboxEvent row = facts.getFirst();
        assertThat(row.getTenantId()).isEqualTo(TENANT_A);
        assertThat(row.getTopic()).isEqualTo("order.events.v1");
        assertThat(row.getRecordKey()).isEqualTo(sessionId.toString());
        JsonNode envelope = objectMapper.readTree(row.getPayload());
        assertThat(envelope.path("aggregateId").stringValue()).isEqualTo(sessionId.toString());
        assertThat(envelope.path("aggregateVersion").longValue()).isZero();
        assertThat(envelope.path("schemaVersion").intValue()).isEqualTo(RegisterSessionOpenedV1.SCHEMA_VERSION);
        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("sessionId").stringValue()).isEqualTo(sessionId.toString());
        assertThat(payload.path("terminalId").stringValue()).isEqualTo(register);
        // The location the open resolved (here the float's, as the request named none).
        assertThat(payload.path("locationId").stringValue()).isEqualTo(shop.toString());
        assertThat(payload.path("openedAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("AC2: a 409 second open and a 422 float-location mismatch queue nothing")
    void refusedOpensQueueNothing() {
        String register = "T-" + UUID.randomUUID();
        String elsewhere = "T-" + UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        asTenant(TENANT_A, () -> {
            floats.saveAndFlush(floatCopy(register, shop));
            floats.saveAndFlush(floatCopy(elsewhere, shop));
            registerSessionService.openSession(new OpenSessionCommand(register, shop));
        });
        assertThat(openedFactCount(register)).isEqualTo(1);

        asTenant(TENANT_A, () -> {
            assertThatThrownBy(() -> registerSessionService.openSession(new OpenSessionCommand(register, shop)))
                    .isInstanceOf(RegisterSessionConflictException.class);
            assertThatThrownBy(() ->
                            registerSessionService.openSession(new OpenSessionCommand(elsewhere, UUID.randomUUID())))
                    .isInstanceOf(RegisterFloatLocationMismatchException.class);
        });

        assertThat(openedFactCount(register)).isEqualTo(1);
        assertThat(openedFactCount(elsewhere)).isZero();
    }

    @Test
    @DisplayName("AC3: the start republish queues one fact per OPEN or CLOSING session of each tenant, none for CLOSED")
    void republishCoversActiveSessionsOfEveryTenant() {
        UUID shop = UUID.randomUUID();
        UUID openA = asTenant(TENANT_A, () -> open("T-" + UUID.randomUUID(), shop));
        UUID closingA = asTenant(TENANT_A, () -> {
            UUID id = open("T-" + UUID.randomUUID(), shop);
            registerSessionService.beginClose(id, new BigDecimal("200.00"));
            return id;
        });
        UUID closedA = asTenant(TENANT_A, () -> {
            UUID id = open("T-" + UUID.randomUUID(), shop);
            registerSessionService.beginClose(id, new BigDecimal("200.00"));
            registerSessionService.confirmClose(id);
            return id;
        });
        UUID openB = asTenant(TENANT_B, () -> open("T-" + UUID.randomUUID(), shop));
        long closingVersion = asTenant(
                TENANT_A, () -> sessions.findById(closingA).orElseThrow().getVersion());

        RegisterSessionFactsBootstrap bootstrap = new RegisterSessionFactsBootstrap(
                new TenantIterator(() -> List.of(TENANT_A, TENANT_B)), sessions, publisher, transactionManager);
        bootstrap.republishAll();

        assertThat(openedFacts(openA)).hasSize(2);
        assertThat(openedFacts(closingA)).hasSize(2);
        assertThat(openedFacts(openB)).hasSize(2);
        // A closed session's open fact is never re-emitted: only the one its open queued.
        assertThat(openedFacts(closedA)).hasSize(1);
        assertThat(openedFacts(openB).getLast().getTenantId()).isEqualTo(TENANT_B);
        assertThat(openedFacts(openA).getLast().getTenantId()).isEqualTo(TENANT_A);
        // Republished at the session's current version (begin-close moved the CLOSING one on).
        JsonNode republished =
                objectMapper.readTree(openedFacts(closingA).getLast().getPayload());
        assertThat(closingVersion).isPositive();
        assertThat(republished.path("aggregateVersion").longValue()).isEqualTo(closingVersion);
    }

    private UUID open(String register, UUID shop) {
        floats.saveAndFlush(floatCopy(register, shop));
        return registerSessionService
                .openSession(new OpenSessionCommand(register, shop))
                .sessionId();
    }

    /** The session's queued opened facts, oldest first (the outbox is a global table, readable unbound). */
    private List<OutboxEvent> openedFacts(UUID sessionId) {
        return outbox.findAll().stream()
                .filter(event -> event.getPayload().contains(OPENED))
                .filter(event -> sessionId.toString().equals(event.getRecordKey()))
                .sorted(java.util.Comparator.comparing(OutboxEvent::getId))
                .toList();
    }

    /** Opened facts queued for the register's sessions (terminal ids are unique per test). */
    private long openedFactCount(String register) {
        return outbox.findAll().stream()
                .filter(event -> event.getPayload().contains(OPENED))
                .filter(event -> event.getPayload().contains("\"" + register + "\""))
                .count();
    }

    private static ExtAccountingRegisterFloat floatCopy(String register, UUID location) {
        return ExtAccountingRegisterFloat.builder()
                .registerFloatId(UUID.randomUUID())
                .registerId(register)
                .locationId(location)
                .amount(new BigDecimal("200.00"))
                .effectiveDate(LocalDate.of(2026, 10, 7))
                .aggregateVersion(1L)
                .syncedAt(Instant.parse("2026-10-07T12:00:00Z"))
                .build();
    }
}
