package com.positivity.order.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.config.OutboxEventWriter;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.OutboxEvent;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.OutboxEventRepository;
import com.positivity.order.internal.service.RegisterSessionService;
import com.positivity.order.internal.service.SessionPolicyService;
import com.positivity.order.internal.service.model.CashMovementCommand;
import com.positivity.order.internal.service.model.OpenSessionCommand;
import com.positivity.order.internal.service.model.UpdateSessionPolicyCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.math.BigDecimal;
import java.time.Clock;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S16 (#2512) on Postgres: the drawer policy and pos-order's copies of accounting's facts are
 * tenant-isolated under row-level security (ADR-0062), and a close queues the schema-2 close fact on
 * the transactional outbox with every movement (ADR-0044 §3, §4). The outbox writer is a Kafka-rails
 * bean the broker-less {@code pg} profile leaves out, so this test wires it in.
 */
@DisplayName("Drawer movements on Postgres (CAP:550 S16)")
@Import(DrawerMovementsIT.OutboxWriterConfig.class)
class DrawerMovementsIT extends PostgresTenancyTestBase {

    @TestConfiguration
    static class OutboxWriterConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock,
                ObjectMapper objectMapper,
                OutboxEventRepository outboxEventRepository,
                TenantResolver tenantResolver) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository, tenantResolver);
        }
    }

    @Autowired
    private SessionPolicyService sessionPolicyService;

    @Autowired
    private RegisterSessionService registerSessionService;

    @Autowired
    private ExtAccountingRegisterFloatRepository floats;

    @Autowired
    private ExtAccountingPettyExpenseCategoryRepository categories;

    @Autowired
    private OutboxEventRepository outbox;

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
    @DisplayName("tenant A's policy, float and category copies are invisible to tenant B")
    void policyAndCopiesAreTenantIsolated() {
        String register = "T-" + UUID.randomUUID();
        asTenant(TENANT_A, () -> {
            sessionPolicyService.update(new UpdateSessionPolicyCommand(
                    true, new BigDecimal("40.00"), false, null, new BigDecimal("3.00"), "Tenant A tightens its count"));
            floats.saveAndFlush(floatCopy(register, "200.00"));
            categories.saveAndFlush(category("IT_" + register.substring(2, 10)));
        });

        asTenant(TENANT_B, () -> {
            assertThat(sessionPolicyService.current().overShortTolerance()).isEqualByComparingTo("5.00");
            assertThat(sessionPolicyService.current().version()).isNull();
            assertThat(sessionPolicyService.history()).isEmpty();
            assertThat(floats.findByRegisterId(register)).isEmpty();
            assertThat(categories.findByCode("IT_" + register.substring(2, 10))).isEmpty();
        });

        asTenant(TENANT_A, () -> {
            assertThat(sessionPolicyService.current().overShortTolerance()).isEqualByComparingTo("3.00");
            assertThat(floats.findByRegisterId(register)).isPresent();
        });
    }

    @Test
    @DisplayName("a close queues order.session.closed schema 2 on the outbox with every movement")
    void closeQueuesVersionTwoFactWithMovements() {
        String register = "T-" + UUID.randomUUID();
        String categoryCode = "IT_" + register.substring(2, 10);
        UUID sessionId = asTenant(TENANT_A, () -> {
            floats.saveAndFlush(floatCopy(register, "200.00"));
            categories.saveAndFlush(category(categoryCode));
            UUID id = registerSessionService
                    .openSession(new OpenSessionCommand(register, null))
                    .sessionId();
            registerSessionService.recordCashMovement(new CashMovementCommand(
                    id,
                    UUID.randomUUID(),
                    "PETTY_EXPENSE",
                    new BigDecimal("12.50"),
                    categoryCode,
                    null,
                    null,
                    "R-IT-1",
                    "gloves",
                    null));
            registerSessionService.recordCashMovement(new CashMovementCommand(
                    id,
                    UUID.randomUUID(),
                    "BANK_DROP",
                    new BigDecimal("100.00"),
                    null,
                    null,
                    "BAG-IT-1",
                    null,
                    null,
                    null));
            // 200.00 − 12.50 − 100.00: counted exactly, so the close needs no variance approval.
            registerSessionService.beginClose(id, new BigDecimal("87.50"));
            registerSessionService.confirmClose(id);
            return id;
        });

        OutboxEvent row = outbox.findAll().stream()
                .filter(event -> event.getPayload().contains("\"order.session.closed\""))
                .filter(event -> event.getPayload().contains(sessionId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(row.getTenantId()).isEqualTo(TENANT_A);
        JsonNode envelope = objectMapper.readTree(row.getPayload());
        assertThat(envelope.path("schemaVersion").intValue()).isEqualTo(2);
        JsonNode payload = envelope.path("payload");
        assertThat(payload.path("openingFloat").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(payload.path("overShort").decimalValue()).isEqualByComparingTo("0.00");
        JsonNode movements = payload.path("movements");
        assertThat(movements.size()).isEqualTo(2);
        assertThat(movements.get(0).path("reason").stringValue()).isEqualTo("PETTY_EXPENSE");
        assertThat(movements.get(0).path("categoryCode").stringValue()).isEqualTo(categoryCode);
        assertThat(movements.get(0).path("clerkId").stringValue()).isEqualTo("cashier-it");
        assertThat(movements.get(1).path("reason").stringValue()).isEqualTo("BANK_DROP");
        assertThat(movements.get(1).path("bagNumber").stringValue()).isEqualTo("BAG-IT-1");
        assertThat(movements.get(1).path("direction").stringValue()).isEqualTo("OUT");
    }

    private static ExtAccountingRegisterFloat floatCopy(String register, String amount) {
        return ExtAccountingRegisterFloat.builder()
                .registerFloatId(UUID.randomUUID())
                .registerId(register)
                .locationId(UUID.randomUUID())
                .amount(new BigDecimal(amount))
                .effectiveDate(LocalDate.of(2026, 10, 7))
                .aggregateVersion(1L)
                .syncedAt(Instant.parse("2026-10-07T12:00:00Z"))
                .build();
    }

    private static ExtAccountingPettyExpenseCategory category(String code) {
        return ExtAccountingPettyExpenseCategory.builder()
                .pettyExpenseCategoryId(UUID.randomUUID())
                .code(code)
                .label("IT category")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .syncedAt(Instant.parse("2026-10-07T12:00:00Z"))
                .build();
    }
}
