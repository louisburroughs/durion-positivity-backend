package com.positivity.warranty;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.warranty.internal.config.JpaConfig;
import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.LineSourceType;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimLineRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import com.positivity.warranty.internal.service.ClaimCodeService;
import com.positivity.warranty.internal.service.ClaimCodeServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Boots the Flyway baseline ({@code V1__baseline_warranty_schema.sql}) against H2 in
 * PostgreSQL mode with {@code ddl-auto=validate}, proving the JPA mappings match the DDL,
 * then exercises the repository finders and the WC-yyyy-nnnnnn claim-code allocator.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_warranty_persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, ClaimCodeServiceImpl.class, WarrantyPersistenceTest.ClockConfig.class})
class WarrantyPersistenceTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private WarrantyProviderRepository providerRepository;

    @Autowired
    private WarrantyClaimRepository claimRepository;

    @Autowired
    private WarrantyClaimLineRepository claimLineRepository;

    @Autowired
    private VendorReimbursementRepository reimbursementRepository;

    @Autowired
    private PartReturnRepository partReturnRepository;

    @Autowired
    private ClaimCodeService claimCodeService;

    @Test
    void claimAggregateRoundTripsWithLinesJsonAndSearch() {
        WarrantyProvider provider = providerRepository.save(WarrantyProvider.builder()
                .name("Michelin")
                .providerType(ProviderType.MANUFACTURER)
                .manufacturerId(UUID.randomUUID())
                .build());
        assertThat(provider.getId()).isNotNull();
        assertThat(provider.getCreatedAt()).isNotNull();
        assertThat(provider.getVersion()).isNotNull();

        UUID customerId = UUID.randomUUID();
        WarrantyClaim claim = WarrantyClaim.builder()
                .claimCode(claimCodeService.nextClaimCode())
                .claimType(ClaimType.MANUFACTURER_DEFECT)
                .customerId(customerId)
                .vehicleId(UUID.randomUUID())
                .vin("1HGCM82633A004352")
                .odometerAtClaim(42_000L)
                .odometerUnit("MILES")
                .originSaleDate(LocalDate.of(2025, 6, 1))
                .providerId(provider.getId())
                .failureDescription("Sidewall separation")
                .photoEvidenceUrls(List.of("https://cdn.example/p1.jpg", "https://cdn.example/p2.jpg"))
                .eligibilityReasons(List.of(Map.of("term", "durationMonths", "pass", true)))
                .suggestedOutcome(Map.of("outcome", "PRORATED_CREDIT", "amount", "84.2100"))
                .build();
        claim.addLine(WarrantyClaimLine.builder()
                .sourceType(LineSourceType.MANUAL)
                .sku("MICH-DEF-225")
                .description("225/45R17 Defender")
                .quantity(new BigDecimal("1.0000"))
                .originalUnitPrice(new BigDecimal("189.9900"))
                .originalTreadDepth(10)
                .measuredTreadDepth(6)
                .prorationPct(new BigDecimal("0.500000"))
                .prorationInputs(Map.of("pullPoint", 2, "originalDepth", 10, "measuredDepth", 6))
                .build());
        claim = claimRepository.saveAndFlush(claim);

        assertThat(claim.getId()).isNotNull();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.DRAFT);
        assertThat(claim.getVersion()).isNotNull();
        assertThat(claimLineRepository.findByClaimId(claim.getId())).hasSize(1);
        assertThat(claimRepository.findByClaimCode(claim.getClaimCode())).isPresent();

        Page<WarrantyClaim> hits =
                claimRepository.search(customerId, null, ClaimStatus.DRAFT, null, PageRequest.of(0, 10));
        assertThat(hits.getTotalElements()).isEqualTo(1);

        WarrantyClaim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertThat(reloaded.getPhotoEvidenceUrls()).hasSize(2);
        assertThat(reloaded.getSuggestedOutcome()).containsEntry("outcome", "PRORATED_CREDIT");

        VendorReimbursement reimbursement = reimbursementRepository.save(VendorReimbursement.builder()
                .claimId(claim.getId())
                .providerId(provider.getId())
                .amountRequested(new BigDecimal("84.2100"))
                .build());
        assertThat(reimbursementRepository.findByStatusAndProviderId(
                        ReimbursementStatus.NOT_SUBMITTED, provider.getId()))
                .extracting(VendorReimbursement::getId)
                .containsExactly(reimbursement.getId());

        UUID lineId = claim.getLines().get(0).getId();
        partReturnRepository.save(PartReturn.builder()
                .claimId(claim.getId())
                .claimLineId(lineId)
                .disposition(PartReturnDisposition.RETURN_TO_VENDOR)
                .build());
        assertThat(partReturnRepository.findByClaimLineId(lineId)).isPresent();
        assertThat(partReturnRepository.findByStatus(PartReturnStatus.AWAITING_PART))
                .hasSize(1);
    }

    @Test
    void claimCodesAreSequentialZeroPaddedAndYearScoped() {
        int year = Year.now(Clock.systemUTC()).getValue();
        String first = claimCodeService.nextClaimCode();
        String second = claimCodeService.nextClaimCode();

        assertThat(first).matches("WC-" + year + "-\\d{6}");
        assertThat(second).matches("WC-" + year + "-\\d{6}");
        long firstSeq = Long.parseLong(first.substring(first.lastIndexOf('-') + 1));
        long secondSeq = Long.parseLong(second.substring(second.lastIndexOf('-') + 1));
        assertThat(secondSeq).isEqualTo(firstSeq + 1);
    }
}
