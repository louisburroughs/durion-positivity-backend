package com.positivity.catalog.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.catalog.BaseIntegrationTest;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignMatchCandidateEntity;
import com.positivity.catalog.internal.enums.MatchTier;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

/**
 * Database-level contract of the enrichment review queries (#1645): which designs the worklist
 * returns for a set of states and a vendor filter, the order a reviewer works down, and the unique
 * constraint that stops a re-match from accumulating duplicate opinions about one (design, product)
 * pair.
 *
 * <p>Runs against the module's real schema through {@link BaseIntegrationTest} rather than a
 * slice: pos-catalog has no {@code spring-boot-data-jpa-test} on its test classpath, and every
 * other real-persistence test here is written the same way.
 */
@DisplayName("Tread-design review queries (#1645)")
class TreadDesignReviewRepositoryTest extends BaseIntegrationTest {

    private static final UUID VENDOR_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");
    private static final UUID VENDOR_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e02");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e03");

    @Autowired
    private TreadDesignRepository treadDesignRepository;

    @Autowired
    private TreadDesignMatchCandidateRepository candidateRepository;

    private TreadDesignEntity design(UUID vendorProfileId, String variantId, TreadDesignMatchState state, String at) {
        return treadDesignRepository.saveAndFlush(TreadDesignEntity.builder()
                .vendorProfileId(vendorProfileId)
                .supplierRef("michelin-eu")
                .vendorVariantId(variantId)
                .brand("Michelin")
                .treadDesign("Pilot Sport 4S")
                .contentHash("hash-" + variantId)
                .hasUnresolvedImages(false)
                .matchState(state)
                .matchStateAt(Instant.parse(at))
                .build());
    }

    @Test
    @DisplayName("the worklist returns only the requested states")
    void filtersByState() {
        design(VENDOR_A, "V-1", TreadDesignMatchState.UNMATCHED, "2026-09-01T00:00:00Z");
        design(VENDOR_A, "V-2", TreadDesignMatchState.REVIEW, "2026-09-02T00:00:00Z");
        design(VENDOR_A, "V-3", TreadDesignMatchState.MATCHED, "2026-09-03T00:00:00Z");
        design(VENDOR_A, "V-4", TreadDesignMatchState.REJECTED, "2026-09-04T00:00:00Z");
        design(VENDOR_A, "V-5", TreadDesignMatchState.DEFERRED, "2026-09-05T00:00:00Z");

        assertThat(treadDesignRepository
                        .findForReview(
                                List.of(TreadDesignMatchState.UNMATCHED, TreadDesignMatchState.REVIEW),
                                null,
                                PageRequest.of(0, 50))
                        .getContent())
                .extracting(TreadDesignEntity::getVendorVariantId)
                .containsExactlyInAnyOrder("V-1", "V-2");

        assertThat(treadDesignRepository
                        .findForReview(List.of(TreadDesignMatchState.DEFERRED), null, PageRequest.of(0, 50))
                        .getContent())
                .extracting(TreadDesignEntity::getVendorVariantId)
                .containsExactly("V-5");
    }

    @Test
    @DisplayName("a null vendor profile means every vendor, not no vendor")
    void vendorFilterIsOptional() {
        design(VENDOR_A, "V-1", TreadDesignMatchState.REVIEW, "2026-09-01T00:00:00Z");
        design(VENDOR_B, "V-2", TreadDesignMatchState.REVIEW, "2026-09-02T00:00:00Z");

        assertThat(treadDesignRepository
                        .findForReview(List.of(TreadDesignMatchState.REVIEW), null, PageRequest.of(0, 50))
                        .getTotalElements())
                .isEqualTo(2);

        assertThat(treadDesignRepository
                        .findForReview(List.of(TreadDesignMatchState.REVIEW), VENDOR_B, PageRequest.of(0, 50))
                        .getContent())
                .extracting(TreadDesignEntity::getVendorProfileId)
                .containsExactly(VENDOR_B);
    }

    @Test
    @DisplayName("the worklist ages on when the decision last moved, most recent first")
    void ordersByStateChangeDescending() {
        design(VENDOR_A, "V-old", TreadDesignMatchState.REVIEW, "2026-09-01T00:00:00Z");
        design(VENDOR_A, "V-new", TreadDesignMatchState.REVIEW, "2026-09-05T00:00:00Z");

        assertThat(treadDesignRepository
                        .findForReview(List.of(TreadDesignMatchState.REVIEW), null, PageRequest.of(0, 50))
                        .getContent())
                .extracting(TreadDesignEntity::getVendorVariantId)
                .containsExactly("V-new", "V-old");
    }

    @Test
    @DisplayName("a design holds one opinion per product — a re-match cannot accumulate duplicates")
    void candidatePairIsUnique() {
        TreadDesignEntity design = design(VENDOR_A, "V-1", TreadDesignMatchState.REVIEW, "2026-09-01T00:00:00Z");
        candidateRepository.saveAndFlush(candidate(design.getId(), PRODUCT_ID, "0.7400", MatchTier.REVIEW));

        assertThatThrownBy(() -> candidateRepository.saveAndFlush(
                        candidate(design.getId(), PRODUCT_ID, "0.9100", MatchTier.AUTO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two designs may each hold an opinion about one product — that is the ambiguity case")
    void twoDesignsMayClaimOneProduct() {
        TreadDesignEntity first = design(VENDOR_A, "V-1", TreadDesignMatchState.REVIEW, "2026-09-01T00:00:00Z");
        TreadDesignEntity second = design(VENDOR_A, "V-2", TreadDesignMatchState.REVIEW, "2026-09-02T00:00:00Z");
        candidateRepository.saveAndFlush(candidate(first.getId(), PRODUCT_ID, "0.9100", MatchTier.AUTO));
        candidateRepository.saveAndFlush(candidate(second.getId(), PRODUCT_ID, "0.9000", MatchTier.AUTO));

        assertThat(candidateRepository.findByProductIdAndTierAndTreadDesignIdNot(
                        PRODUCT_ID, MatchTier.AUTO, first.getId()))
                .extracting(TreadDesignMatchCandidateEntity::getTreadDesignId)
                .containsExactly(second.getId());
    }

    @Test
    @DisplayName("candidates come back best first, so a reviewer reads the strongest suggestion first")
    void candidatesAreOrderedByScore() {
        TreadDesignEntity design = design(VENDOR_A, "V-1", TreadDesignMatchState.REVIEW, "2026-09-01T00:00:00Z");
        UUID otherProduct = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e04");
        candidateRepository.saveAndFlush(candidate(design.getId(), PRODUCT_ID, "0.6100", MatchTier.REVIEW));
        candidateRepository.saveAndFlush(candidate(design.getId(), otherProduct, "0.9100", MatchTier.AUTO));

        assertThat(candidateRepository.findByTreadDesignIdOrderByScoreDesc(design.getId()))
                .extracting(TreadDesignMatchCandidateEntity::getProductId)
                .containsExactly(otherProduct, PRODUCT_ID);
    }

    private static TreadDesignMatchCandidateEntity candidate(
            UUID designId, UUID productId, String score, MatchTier tier) {
        return TreadDesignMatchCandidateEntity.builder()
                .treadDesignId(designId)
                .productId(productId)
                .score(new BigDecimal(score))
                .tier(tier)
                .build();
    }
}
