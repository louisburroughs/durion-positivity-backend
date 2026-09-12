package com.positivity.order.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.PostgresSliceTestBase;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The cart search ({@link SalesOrderRepository#search}) against the real PostgreSQL schema,
 * exercising every combination of its all-optional filters.
 *
 * <p>This is a database test rather than a mocked one because what it guards is a property of the
 * database, and the property is the opposite of the one that broke the pricing overlap guards in
 * {@code pos-price}. {@code search} is still one JPQL string of {@code (:param IS NULL OR column =
 * :param)} clauses and it is deliberately left that way: PostgreSQL rejects that shape at parse
 * time only when the placeholder's type cannot be inferred, which is the case for a temporal
 * parameter and not for these three — two {@code String}s and an enum, all bound as varchar with a
 * concrete type OID (issue #1891, PR #1961, PR #1963). The query works, so it was not rewritten.
 *
 * <p>What this test pins is that it goes on working. Adding an optional filter of a type the driver
 * leaves untyped — an {@code Instant} window over {@code createdAt} is the obvious next filter a
 * cart worklist would want — would break every call to {@code GET /v1/orders/carts}, supplied
 * filter or not, and only a test that issues the statement to PostgreSQL can see it.
 */
@DisplayName("Sales order cart search on PostgreSQL")
class SalesOrderRepositoryTest extends PostgresSliceTestBase {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String LANE_1 = "lane-1";
    private static final String LANE_2 = "lane-2";

    @Autowired
    private SalesOrderRepository orders;

    private SalesOrder order(String clerkId, String terminalId, SalesOrderStatus status) {
        return orders.saveAndFlush(SalesOrder.builder()
                .clerkId(clerkId)
                .terminalId(terminalId)
                .status(status)
                .subtotal(new BigDecimal("10.0000"))
                .createdBy("test")
                .updatedBy("test")
                .build());
    }

    @Test
    @DisplayName("an unfiltered search pages every order")
    void unfilteredSearchReturnsEveryOrder() {
        order(ALICE, LANE_1, SalesOrderStatus.DRAFT);
        order(BOB, LANE_2, SalesOrderStatus.COMPLETED);

        assertThat(orders.search(null, null, null, PageRequest.of(0, 10)).getContent())
                .hasSize(2);
    }

    @Test
    @DisplayName("each filter narrows the search on its own")
    void eachFilterNarrowsTheSearchOnItsOwn() {
        SalesOrder draft = order(ALICE, LANE_1, SalesOrderStatus.DRAFT);
        order(BOB, LANE_2, SalesOrderStatus.COMPLETED);

        assertThat(orders.search(ALICE, null, null, PageRequest.of(0, 10)).getContent())
                .containsExactly(draft);
        assertThat(orders.search(null, LANE_1, null, PageRequest.of(0, 10)).getContent())
                .containsExactly(draft);
        assertThat(orders.search(null, null, SalesOrderStatus.DRAFT, PageRequest.of(0, 10))
                        .getContent())
                .containsExactly(draft);
    }

    @Test
    @DisplayName("the filters combine")
    void filtersCombine() {
        SalesOrder wanted = order(ALICE, LANE_1, SalesOrderStatus.DRAFT);
        order(BOB, LANE_1, SalesOrderStatus.DRAFT);
        order(ALICE, LANE_2, SalesOrderStatus.DRAFT);
        order(ALICE, LANE_1, SalesOrderStatus.QUOTED);

        assertThat(orders.search(ALICE, LANE_1, SalesOrderStatus.DRAFT, PageRequest.of(0, 10))
                        .getContent())
                .containsExactly(wanted);
    }

    @Test
    @DisplayName("a filter matching nothing returns an empty page rather than everything")
    void unmatchedFilterReturnsEmptyPage() {
        order(ALICE, LANE_1, SalesOrderStatus.DRAFT);

        assertThat(orders.search("nobody", null, null, PageRequest.of(0, 10)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("the page is a window on the whole match, and the total counts all of it")
    void pagingReportsTheWholeMatch() {
        order(ALICE, LANE_1, SalesOrderStatus.DRAFT);
        order(ALICE, LANE_1, SalesOrderStatus.DRAFT);
        order(ALICE, LANE_1, SalesOrderStatus.DRAFT);

        var page = orders.search(ALICE, null, null, PageRequest.of(0, 2));

        // The count query Spring Data derives is the second statement this method issues, and it
        // carries the same placeholders; a shape PostgreSQL cannot parse would fail there too.
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unpaged search returns every match rather than failing")
    void unpagedSearchReturnsEveryMatch() {
        order(ALICE, LANE_1, SalesOrderStatus.DRAFT);
        order(BOB, LANE_2, SalesOrderStatus.DRAFT);

        assertThat(orders.search(null, null, null, Pageable.unpaged()).getContent())
                .hasSize(2);
    }
}
