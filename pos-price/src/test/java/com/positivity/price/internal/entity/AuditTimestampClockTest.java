package com.positivity.price.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.price.internal.config.JpaAuditingConfig;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.enums.ServiceTag;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Audit timestamps must come from the injected application {@link Clock}, not wall time.
 *
 * <p>These two entities previously carried Hibernate's {@code @CreationTimestamp} and
 * {@code @UpdateTimestamp}, which are produced by Hibernate's own generator and never consult the
 * Spring {@code Clock} bean. The clock here is fixed one year in the past, where an accelerated run
 * starts, so a value produced by the old generators would land on today's date and fail.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_price_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            // Schema from the entity mappings: this test is about where the timestamp value comes
            // from, and the module's Flyway baseline is PostgreSQL-only.
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, AuditTimestampClockTest.FixedClockConfig.class})
class AuditTimestampClockTest {

    /** One year before the run, where an accelerated deployment anchors virtual time. */
    private static final Instant VIRTUAL_NOW = Instant.parse("2025-08-20T12:00:00Z");

    private static final Instant LATER = Instant.parse("2025-11-03T08:30:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableClock clock;

    @Test
    @DisplayName("PromotionOffer stamps createdAt/updatedAt from the injected clock")
    void promotionOffer_usesInjectedClockOnInsertAndUpdate() {
        clock.setInstant(VIRTUAL_NOW);

        PromotionOffer offer = new PromotionOffer();
        offer.setName("Audit clock offer");
        offer.setPromoCode("AUDIT-" + UUID.randomUUID());
        offer.setDiscountType(DiscountType.PERCENT_LABOR);
        offer.setDiscountValue(new BigDecimal("10.00"));
        offer.setStartDate(LocalDate.parse("2025-01-01"));
        offer.setEndDate(LocalDate.parse("2025-12-31"));
        offer.setStatus(PromotionStatus.DRAFT);

        PromotionOffer saved = entityManager.merge(offer);
        entityManager.flush();
        UUID id = saved.getPromotionOfferId();
        entityManager.clear();

        PromotionOffer reloaded = entityManager.find(PromotionOffer.class, id);
        assertThat(reloaded.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(VIRTUAL_NOW);

        clock.setInstant(LATER);
        reloaded.setStatus(PromotionStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        PromotionOffer updated = entityManager.find(PromotionOffer.class, id);
        assertThat(updated.getUpdatedAt()).isEqualTo(LATER);
        assertThat(updated.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
    }

    @Test
    @DisplayName("RestrictionRule stamps createdAt/updatedAt from the injected clock")
    void restrictionRule_usesInjectedClockOnInsertAndUpdate() {
        clock.setInstant(VIRTUAL_NOW);

        RestrictionRule rule = new RestrictionRule();
        rule.setProductId(UUID.randomUUID());
        rule.setLocationTag(LocationTag.ALL_LOCATIONS);
        rule.setServiceTag(ServiceTag.POS_SALE);
        rule.setEffectiveFrom(LocalDate.parse("2025-01-01"));
        rule.setActive(true);
        rule.setOverrideable(true);
        rule.setPolicyVersion(1);

        RestrictionRule saved = entityManager.merge(rule);
        entityManager.flush();
        UUID id = saved.getRuleId();
        entityManager.clear();

        RestrictionRule reloaded = entityManager.find(RestrictionRule.class, id);
        assertThat(reloaded.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(VIRTUAL_NOW);

        clock.setInstant(LATER);
        reloaded.setActive(false);
        entityManager.flush();
        entityManager.clear();

        RestrictionRule updated = entityManager.find(RestrictionRule.class, id);
        assertThat(updated.getUpdatedAt()).isEqualTo(LATER);
        assertThat(updated.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        MutableClock clock() {
            return new MutableClock(VIRTUAL_NOW);
        }
    }

    /** A clock the test moves explicitly, so no assertion depends on real elapsed time. */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
