package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TreadDesignServiceImpl — enrichment read surface (CAP-324 #1352)")
class TreadDesignServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID DESIGN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TreadDesignRepository treadDesignRepository;

    @Mock
    private TreadDesignTextRepository treadDesignTextRepository;

    @Mock
    private TreadDesignImageRepository treadDesignImageRepository;

    private TreadDesignServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TreadDesignServiceImpl(
                productRepository, treadDesignRepository, treadDesignTextRepository, treadDesignImageRepository);
    }

    private static TreadDesignEntity design() {
        return TreadDesignEntity.builder()
                .id(DESIGN_ID)
                .vendorProfileId(UUID.randomUUID())
                .supplierRef("michelin-eu")
                .vendorVariantId("VAR-1")
                .brand("Michelin")
                .treadDesign("Pilot Sport 4S")
                .hasUnresolvedImages(false)
                .updatedAt(Instant.parse("2026-08-18T09:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("a product with no matched design returns empty rather than throwing")
    void unmatchedProductReturnsEmpty() {
        ProductEntity product = new ProductEntity();
        product.setId(PRODUCT_ID);
        product.setTreadDesignId(null);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThat(service.findForProduct(PRODUCT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a matched product returns the design's texts and images, vendor-supplied")
    void matchedProductReturnsEnrichment() {
        ProductEntity product = new ProductEntity();
        product.setId(PRODUCT_ID);
        product.setTreadDesignId(DESIGN_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(treadDesignRepository.findById(DESIGN_ID)).thenReturn(Optional.of(design()));
        when(treadDesignTextRepository.findByTreadDesignId(DESIGN_ID))
                .thenReturn(List.of(TreadDesignTextEntity.builder()
                        .treadDesignId(DESIGN_ID)
                        .languageCode("en-US")
                        .name("Pilot Sport 4S")
                        .build()));
        when(treadDesignImageRepository.findByTreadDesignId(DESIGN_ID))
                .thenReturn(List.of(TreadDesignImageEntity.builder()
                        .treadDesignId(DESIGN_ID)
                        .imageId(42L)
                        .unresolved(false)
                        .build()));

        Optional<TreadDesignDto> result = service.findForProduct(PRODUCT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().brand()).isEqualTo("Michelin");
        assertThat(result.get().texts())
                .singleElement()
                .satisfies(text -> assertThat(text.languageCode()).isEqualTo("en-US"));
        assertThat(result.get().images())
                .singleElement()
                .satisfies(image -> assertThat(image.imageId()).isEqualTo(42L));
    }

    @Test
    @DisplayName("a product that does not exist returns empty rather than throwing")
    void missingProductReturnsEmpty() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThat(service.findForProduct(PRODUCT_ID)).isEmpty();
    }

    @Test
    @DisplayName("unmatched designs are listed for review")
    void findUnmatchedDelegatesToRepository() {
        when(treadDesignRepository.findUnmatched(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(design())));

        assertThat(service.findUnmatched(PageRequest.of(0, 50)).getContent()).hasSize(1);
    }
}
