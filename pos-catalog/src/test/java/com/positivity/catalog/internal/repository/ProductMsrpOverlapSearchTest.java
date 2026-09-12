package com.positivity.catalog.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.PostgresSliceTestBase;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The overlap check an MSRP record is admitted by, against the real PostgreSQL baseline.
 *
 * <h2>What this defends</h2>
 *
 * The check was written as one JPQL string of {@code (:param IS NULL OR …)} clauses. PostgreSQL
 * rejected it at parse time with {@code could not determine data type of parameter $4} — the {@code
 * IS NULL} test on {@code endDate} — but only when a value was bound there, because the pgjdbc
 * driver types a null {@link LocalDate} and leaves a supplied one unspecified. So posting an MSRP
 * with an {@code effectiveEndDate} was a 500 and the same call without one succeeded, while the
 * H2-backed tests passed either way (issue #1891).
 *
 * <p>That asymmetry is why both the closed and the open-ended candidate are asserted here, and why
 * these run on PostgreSQL: on H2 the whole class would pass against the defect.
 */
@DisplayName("MSRP overlap search on PostgreSQL (#1891)")
class ProductMsrpOverlapSearchTest extends PostgresSliceTestBase {

    private static final LocalDate JAN = LocalDate.parse("2030-01-01");
    private static final LocalDate FEB = LocalDate.parse("2030-02-01");
    private static final LocalDate MAR = LocalDate.parse("2030-03-01");
    private static final LocalDate APR = LocalDate.parse("2030-04-01");

    @Autowired
    private ProductRepository products;

    @Autowired
    private ProductMsrpRepository msrps;

    private ProductEntity product;

    @BeforeEach
    void createProduct() {
        ProductEntity entity = new ProductEntity();
        entity.setName("msrp-overlap");
        entity.setSku("MSRP-" + UUID.randomUUID());
        product = products.saveAndFlush(entity);
    }

    private ProductMsrpEntity msrp(LocalDate start, LocalDate end) {
        ProductMsrpEntity entity = new ProductMsrpEntity();
        entity.setProduct(product);
        entity.setAmount(new BigDecimal("19.9900"));
        entity.setCurrency("USD");
        entity.setEffectiveStartDate(start);
        entity.setEffectiveEndDate(end);
        return msrps.saveAndFlush(entity);
    }

    @Test
    void findsTheOverlapForAClosedCandidateAndLeavesTheLaterRecordAlone() {
        ProductMsrpEntity january = msrp(JAN, FEB);
        msrp(MAR, APR);

        // The call that used to be a 500: a candidate carrying an end date.
        assertThat(msrps.findOverlapping(product.getId(), JAN, FEB, null))
                .as("the record covering the same window overlaps; the March record does not")
                .extracting(ProductMsrpEntity::getMsrpId)
                .containsExactly(january.getMsrpId());
    }

    @Test
    void treatsAnOpenEndedCandidateAsUnboundedAbove() {
        ProductMsrpEntity january = msrp(JAN, FEB);
        ProductMsrpEntity march = msrp(MAR, APR);

        assertThat(msrps.findOverlapping(product.getId(), JAN, null, null))
                .as("a candidate with no end date overlaps everything from its start onwards")
                .extracting(ProductMsrpEntity::getMsrpId)
                .containsExactlyInAnyOrder(january.getMsrpId(), march.getMsrpId());
    }

    @Test
    void treatsAnOpenEndedRecordAsOverlappingEveryLaterCandidate() {
        ProductMsrpEntity openEnded = msrp(JAN, null);

        assertThat(msrps.findOverlapping(product.getId(), MAR, APR, null))
                .as("a record that never ends is still in force two months later")
                .extracting(ProductMsrpEntity::getMsrpId)
                .containsExactly(openEnded.getMsrpId());
    }

    @Test
    void excludesTheRecordBeingUpdatedFromItsOwnOverlapCheck() {
        ProductMsrpEntity january = msrp(JAN, FEB);

        assertThat(msrps.findOverlapping(product.getId(), JAN, FEB, january.getMsrpId()))
                .as("a record being updated does not overlap itself")
                .isEmpty();
    }

    @Test
    void scopesTheCheckToOneProduct() {
        msrp(JAN, FEB);

        ProductEntity other = new ProductEntity();
        other.setName("msrp-overlap-other");
        other.setSku("MSRP-" + UUID.randomUUID());
        UUID otherId = products.saveAndFlush(other).getId();

        assertThat(msrps.findOverlapping(otherId, JAN, FEB, null))
                .as("another product's window is not this product's conflict")
                .isEmpty();
    }
}
