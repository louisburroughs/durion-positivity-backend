package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.mktcat.service.model.MarketingEnrichmentView;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/**
 * {@link MktCatStagedReader#findStaged} — plain listing and the {@code hasUnresolvedImages} filter
 * added for #1645 (issue #1638 decision 4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MktCatStagedReader — staged variant reads")
class MktCatStagedReaderTest {

    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final SupplierRef SUPPLIER = new SupplierRef("ediwheel-net");

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private SupplierMktCatVariantRepository variantRepository;

    private MktCatStagedReader reader;

    @BeforeEach
    void setUp() {
        reader = new MktCatStagedReader(profileResolver, variantRepository);
        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.MARKETING_CATALOG))
                .thenReturn(binding());
    }

    @Test
    void withNoFilterListsEveryStagedVariant() {
        when(variantRepository.findByVendorProfileIdOrderByLastSeenAtDesc(eq(PROFILE_ID), any(Limit.class)))
                .thenReturn(List.of(variant("v1", true), variant("v2", false)));

        List<MarketingEnrichmentView> result = reader.findStaged(SUPPLIER, 100);

        assertThat(result).extracting(MarketingEnrichmentView::vendorVariantId).containsExactly("v1", "v2");
        verify(variantRepository, never())
                .findByVendorProfileIdAndHasUnresolvedImagesOrderByLastSeenAtDesc(any(), anyBoolean(), any());
    }

    @Test
    void withNullFilterBehavesLikeTheTwoArgOverload() {
        when(variantRepository.findByVendorProfileIdOrderByLastSeenAtDesc(eq(PROFILE_ID), any(Limit.class)))
                .thenReturn(List.of(variant("v1", true)));

        List<MarketingEnrichmentView> result = reader.findStaged(SUPPLIER, 100, null);

        assertThat(result).extracting(MarketingEnrichmentView::vendorVariantId).containsExactly("v1");
        verify(variantRepository, never())
                .findByVendorProfileIdAndHasUnresolvedImagesOrderByLastSeenAtDesc(any(), anyBoolean(), any());
    }

    @Test
    void filtersToVariantsStillMissingArtworkWhenTrue() {
        when(variantRepository.findByVendorProfileIdAndHasUnresolvedImagesOrderByLastSeenAtDesc(
                        eq(PROFILE_ID), eq(true), any(Limit.class)))
                .thenReturn(List.of(variant("v1", true)));

        List<MarketingEnrichmentView> result = reader.findStaged(SUPPLIER, 100, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).unresolvedImages()).isTrue();
        verify(variantRepository, never()).findByVendorProfileIdOrderByLastSeenAtDesc(any(), any());
    }

    @Test
    void filtersToVariantsWithResolvedArtworkWhenFalse() {
        when(variantRepository.findByVendorProfileIdAndHasUnresolvedImagesOrderByLastSeenAtDesc(
                        eq(PROFILE_ID), eq(false), any(Limit.class)))
                .thenReturn(List.of(variant("v2", false)));

        List<MarketingEnrichmentView> result = reader.findStaged(SUPPLIER, 100, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).unresolvedImages()).isFalse();
        verify(variantRepository, never()).findByVendorProfileIdOrderByLastSeenAtDesc(any(), any());
    }

    private static SupplierMktCatVariantEntity variant(String vendorVariantId, boolean hasUnresolvedImages) {
        return SupplierMktCatVariantEntity.builder()
                .supplierMktCatVariantId(UUID.randomUUID())
                .vendorProfileId(PROFILE_ID)
                .supplierRef(SUPPLIER.value())
                .vendorVariantId(vendorVariantId)
                .brand("Michelin")
                .treadDesign("Pilot Sport 4S")
                .contentHash("hash-" + vendorVariantId)
                .textsJson("[]")
                .imagesJson("[]")
                .hasUnresolvedImages(hasUnresolvedImages)
                .firstSeenAt(Instant.parse("2026-08-01T00:00:00Z"))
                .lastSeenAt(Instant.parse("2026-08-16T10:00:00Z"))
                .build();
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef(SUPPLIER.value());
        return new ResolvedBinding(
                profile,
                new SupplierEndpointBindingEntity(),
                new SupplierAuthConfigEntity(),
                SupplierCapability.MARKETING_CATALOG,
                ProtocolFamily.EDIWHEEL_JSON,
                new ProtocolVersion("C1.2"));
    }
}
