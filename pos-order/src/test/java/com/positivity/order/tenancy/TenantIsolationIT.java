package com.positivity.order.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.OrderNumberSequence;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.WalkInUnavailableException;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.OrderNumberSequenceRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.HouseAccountReplica;
import com.positivity.order.internal.service.SalesOrderService;
import com.positivity.order.internal.service.model.SalesOrderSummary;
import com.positivity.order.internal.service.model.SetCartCustomerCommand;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-order)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    private static final String PERIOD = "2609";

    @Autowired
    private OrderNumberSequenceRepository sequences;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ExtCustomerRepository customers;

    @Autowired
    private SalesOrderRepository orders;

    @Autowired
    private HouseAccountReplica houseAccounts;

    @Autowired
    private SalesOrderService salesOrderService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID locationId = UUID.randomUUID();
        OrderNumberSequence.Key key = new OrderNumberSequence.Key(locationId, PERIOD);
        asTenant(TENANT_A, () -> sequences.saveAndFlush(new OrderNumberSequence(key, 7L)));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(sequences.findById(key))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(sequences.findById(key).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByLocation(jdbc, locationId))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(sequences.findById(key))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByLocation(jdbc, locationId))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update(
                            "UPDATE order_number_sequence SET next_value = 99 WHERE location_id = ? AND period = ?",
                            locationId,
                            PERIOD))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByLocation(jdbc, locationId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO order_number_sequence (location_id, period, next_value) VALUES (?, ?, 1)",
                        UUID.randomUUID(),
                        PERIOD))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(sequences.findById(key).orElseThrow().getNextValue())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo(7L));
    }

    /**
     * CAP:550 S8 AC9: Walk-in resolves the bound tenant's CASH house account and nobody else's. A
     * tenant whose replica holds none is refused ({@code ORDER_WALK_IN_UNAVAILABLE}) even while
     * another tenant's house account sits in the same table, and once it has its own, that is the
     * one resolved.
     */
    @Test
    void walkInResolvesOnlyTheBoundTenantsHouseAccount() {
        UUID houseA = UUID.randomUUID();
        UUID houseB = UUID.randomUUID();
        asTenant(TENANT_A, () -> customers.saveAndFlush(houseAccount(houseA)));

        AtomicReference<UUID> cartB = new AtomicReference<>();
        asTenant(TENANT_B, () -> {
            assertThat(houseAccounts.findActiveCashSale())
                    .as("tenant B holds no house account; tenant A's is invisible")
                    .isEmpty();
            assertThat(houseAccounts.isCashSale(houseA))
                    .as("tenant A's house account is not a walk-in customer for tenant B")
                    .isFalse();

            cartB.set(orders.saveAndFlush(draftCart()).getOrderId());
            assertThatThrownBy(() -> salesOrderService.setCartCustomer(
                            cartB.get(), new SetCartCustomerCommand(null, true, null)))
                    .isInstanceOf(WalkInUnavailableException.class);
            assertThat(orders.findById(cartB.get()).orElseThrow().getCustomerId())
                    .as("no other tenant's house account was put on the cart")
                    .isNull();

            customers.saveAndFlush(houseAccount(houseB));
            SalesOrderSummary walkIn =
                    salesOrderService.setCartCustomer(cartB.get(), new SetCartCustomerCommand(null, true, null));
            assertThat(walkIn.customerId()).isEqualTo(houseB.toString());
            assertThat(walkIn.walkIn()).isTrue();
        });

        asTenant(
                TENANT_A,
                () -> assertThat(
                                houseAccounts.findActiveCashSale().orElseThrow().getPartyId())
                        .as("tenant A still resolves its own")
                        .isEqualTo(houseA));
    }

    private static ExtCustomer houseAccount(UUID partyId) {
        return ExtCustomer.builder()
                .partyId(partyId)
                .status("ACTIVE")
                .displayName("Walk-in customer")
                .partyType("COMMERCIAL")
                .requirementsMet(true)
                .houseAccount(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE)
                .aggregateVersion(1)
                .syncedAt(Instant.now())
                .build();
    }

    private static SalesOrder draftCart() {
        return SalesOrder.builder()
                .clerkId("clerk-tenancy")
                .terminalId("terminal-tenancy")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO.setScale(4))
                .createdBy("tenancy-it")
                .updatedBy("tenancy-it")
                .build();
    }

    private static int countByLocation(JdbcTemplate jdbc, UUID locationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM order_number_sequence WHERE location_id = ?", Integer.class, locationId);
        return count == null ? 0 : count;
    }
}
