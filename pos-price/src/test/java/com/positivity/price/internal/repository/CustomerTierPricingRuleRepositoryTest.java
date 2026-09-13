package com.positivity.price.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.price.PostgresSliceTestBase;
import com.positivity.price.internal.entity.CustomerTierPricingRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The customer-tier overlap guard ({@link
 * CustomerTierPricingRuleRepository#existsOverlappingEffectiveWindow}) against the real PostgreSQL
 * schema.
 *
 * <p>This is a database test rather than a mocked one because what broke this query was a property
 * of the database. It was one JPQL string of {@code (:param IS NULL OR …)} clauses, and one of
 * those placeholders was an {@link Instant}: PostgreSQL then rejected the statement at parse time,
 * for every call, with {@code could not determine data type of parameter $n} (issue #1891). The
 * H2-backed test of the same query stayed green throughout. See {@link EffectiveWindowOverlapSearch}
 * for why the placeholder is unresolvable and why the filter is a specification now.
 */
@DisplayName("Customer tier overlap window guard on PostgreSQL")
class CustomerTierPricingRuleRepositoryTest extends PostgresSliceTestBase {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant APR = Instant.parse("2026-04-01T00:00:00Z");

    private final UUID productId = UUID.randomUUID();
    private final UUID customerTierId = UUID.randomUUID();

    @Autowired
    private CustomerTierPricingRuleRepository rules;

    private CustomerTierPricingRule rule(Instant effectiveFrom, Instant effectiveTo) {
        CustomerTierPricingRule rule = new CustomerTierPricingRule();
        rule.setProductId(productId);
        rule.setCustomerTierId(customerTierId);
        rule.setDiscountRate(new BigDecimal("0.1000"));
        rule.setEffectiveFrom(effectiveFrom);
        rule.setEffectiveTo(effectiveTo);
        return rules.saveAndFlush(rule);
    }

    @Test
    @DisplayName("a window overlapping an existing rule is detected")
    void overlappingWindowIsDetected() {
        rule(JAN, MAR);

        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, FEB, APR))
                .isTrue();
    }

    @Test
    @DisplayName("the interval is half-open, so a window starting where one ends does not overlap")
    void adjacentWindowDoesNotOverlap() {
        rule(JAN, FEB);

        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, FEB, MAR))
                .isFalse();
    }

    @Test
    @DisplayName("an open-ended candidate window overlaps everything from its start onwards")
    void openEndedCandidateWindowOverlapsLaterRules() {
        rule(MAR, APR);

        // A null effectiveTo is the case the (:effectiveTo IS NULL OR …) clause existed to serve,
        // and the case whose untyped placeholder made PostgreSQL reject the whole statement.
        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, JAN, null))
                .isTrue();
    }

    @Test
    @DisplayName("an open-ended stored rule is overlapped by any later window")
    void openEndedStoredRuleIsOverlapped() {
        rule(JAN, null);

        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, MAR, APR))
                .isTrue();
    }

    @Test
    @DisplayName("both windows open-ended still overlap")
    void bothWindowsOpenEnded() {
        rule(JAN, null);

        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, MAR, null))
                .isTrue();
    }

    @Test
    @DisplayName("the rule being edited is excluded from its own overlap check")
    void excludedRuleDoesNotOverlapItself() {
        CustomerTierPricingRule existing = rule(JAN, MAR);

        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, JAN, MAR, existing.getId()))
                .isFalse();
        assertThat(rules.existsOverlappingEffectiveWindow(productId, customerTierId, JAN, MAR, null))
                .isTrue();
    }

    @Test
    @DisplayName("another product's rules never overlap")
    void otherProductsDoNotOverlap() {
        rule(JAN, MAR);

        assertThat(rules.existsOverlappingEffectiveWindow(UUID.randomUUID(), customerTierId, JAN, MAR))
                .isFalse();
        assertThat(rules.existsOverlappingEffectiveWindow(productId, UUID.randomUUID(), JAN, MAR))
                .isFalse();
    }
}
