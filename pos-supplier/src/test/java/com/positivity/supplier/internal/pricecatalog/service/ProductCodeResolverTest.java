package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.pricecatalog.service.ProductCodeResolver.Resolution;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Replica-backed product-code resolution (#1224, ADR-0044 R3)")
class ProductCodeResolverTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");

    @Mock
    private ExtProductCodeReplicaRepository replicaRepository;

    @InjectMocks
    private ProductCodeResolver resolver;

    private static ExtProductCodeReplica row(UUID productId, String codeType, String code) {
        return ExtProductCodeReplica.builder()
                .productId(productId)
                .codeType(codeType)
                .code(code)
                .aggregateVersion(1L)
                .build();
    }

    @Test
    void resolvesTheSingleProductCarryingTheCode() {
        when(replicaRepository.count()).thenReturn(12L);
        when(replicaRepository.findByCodeTypeAndCode("EAN", "3528709999083"))
                .thenReturn(List.of(row(PRODUCT_ID, "EAN", "3528709999083")));

        assertThat(resolver.resolve("EAN", "3528709999083")).isEqualTo(new Resolution.Matched(PRODUCT_ID));
    }

    @Test
    void trimsTheCodeBeforeMatching() {
        when(replicaRepository.count()).thenReturn(12L);
        when(replicaRepository.findByCodeTypeAndCode("UPC", "0123456789012"))
                .thenReturn(List.of(row(PRODUCT_ID, "UPC", "0123456789012")));

        assertThat(resolver.resolve("UPC", "  0123456789012 ")).isInstanceOf(Resolution.Matched.class);
    }

    @Test
    void reportsAMissWhenNoProductCarriesTheCode() {
        when(replicaRepository.count()).thenReturn(12L);
        when(replicaRepository.findByCodeTypeAndCode("EAN", "0000000000000")).thenReturn(List.of());

        assertThat(resolver.resolve("EAN", "0000000000000")).isInstanceOf(Resolution.NotFound.class);
    }

    @Test
    void reportsAnEmptyReplicaAsUnavailableRatherThanAsACatalogFullOfMisses() {
        when(replicaRepository.count()).thenReturn(0L);

        assertThat(resolver.resolve("EAN", "3528709999083")).isInstanceOf(Resolution.Unavailable.class);
    }

    @Test
    void reportsADuplicatedReplicaRowAsAmbiguousRatherThanPickingOne() {
        when(replicaRepository.count()).thenReturn(12L);
        when(replicaRepository.findByCodeTypeAndCode("EAN", "3528709999083"))
                .thenReturn(List.of(
                        row(PRODUCT_ID, "EAN", "3528709999083"), row(OTHER_PRODUCT_ID, "EAN", "3528709999083")));

        assertThat(resolver.resolve("EAN", "3528709999083")).isInstanceOf(Resolution.Ambiguous.class);
    }

    @Test
    void treatsABlankCodeAsAMissWithoutQueryingTheReplica() {
        assertThat(resolver.resolve("EAN", "   ")).isInstanceOf(Resolution.NotFound.class);
    }
}
