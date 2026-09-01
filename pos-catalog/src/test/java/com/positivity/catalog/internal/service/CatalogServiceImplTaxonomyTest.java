package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.enums.OperationCategory;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CatalogRepository;
import com.positivity.catalog.internal.repository.NonInventoryProductRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Service operation taxonomy on the catalog-item write path (#1569 Phase 0): operationCode,
 * operationCategory and defaultLaborHours must round-trip through create/update, and malformed
 * values must answer 400 — not land in the system of record.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CatalogServiceImpl service operation taxonomy")
class CatalogServiceImplTaxonomyTest {

    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b01");
    private static final UUID OTHER_SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9b02");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private NonInventoryProductRepository nonInventoryProductRepository;

    @Mock
    private CatalogRepository catalogRepository;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    private CatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CatalogServiceImpl(
                productRepository,
                serviceRepository,
                nonInventoryProductRepository,
                catalogRepository,
                catalogFactPublisher);
        when(serviceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(serviceRepository.findByOperationCode(any())).thenReturn(Optional.empty());
    }

    private static CatalogItemRequestDto serviceRequest() {
        CatalogItemRequestDto request = new CatalogItemRequestDto();
        request.setName("Brake Pad Replacement - Front");
        request.setShortDescription("Front brake pad set replacement");
        request.setLongDescription("Replace front brake pads");
        return request;
    }

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("create persists and returns the three taxonomy fields")
        void createRoundTripsTaxonomyFields() {
            CatalogItemRequestDto request = serviceRequest();
            request.setOperationCode("BRAKE-PAD-FRONT");
            request.setOperationCategory("REPAIR");
            request.setDefaultLaborHours(new BigDecimal("1.5"));

            CatalogItemResponseDto response = service.addCatalogItem("service", request);

            assertThat(response.getOperationCode()).isEqualTo("BRAKE-PAD-FRONT");
            assertThat(response.getOperationCategory()).isEqualTo("REPAIR");
            assertThat(response.getDefaultLaborHours()).isEqualByComparingTo("1.5");
        }

        @Test
        @DisplayName("absent taxonomy fields stay null rather than defaulting")
        void absentFieldsStayNull() {
            CatalogItemResponseDto response = service.addCatalogItem("service", serviceRequest());

            assertThat(response.getOperationCode()).isNull();
            assertThat(response.getOperationCategory()).isNull();
            assertThat(response.getDefaultLaborHours()).isNull();
        }

        @Test
        @DisplayName("update overwrites taxonomy fields like the other full-replacement fields")
        void updateOverwritesTaxonomyFields() {
            ServiceEntity existing = new ServiceEntity();
            existing.setId(SERVICE_ID);
            existing.setOperationCode("OLD-CODE");
            existing.setOperationCategory(OperationCategory.MAINTENANCE);
            existing.setDefaultLaborHours(new BigDecimal("2.0"));
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(existing));

            CatalogItemRequestDto request = serviceRequest();
            request.setOperationCode("TIRE-ROTATION");
            request.setOperationCategory("tire_service");

            CatalogItemResponseDto response =
                    service.updateCatalogItem("service", SERVICE_ID, request).orElseThrow();

            assertThat(response.getOperationCode()).isEqualTo("TIRE-ROTATION");
            assertThat(response.getOperationCategory()).isEqualTo("TIRE_SERVICE");
            assertThat(response.getDefaultLaborHours()).isNull();
        }
    }

    @Nested
    @DisplayName("operationCode validation")
    class OperationCodeValidation {

        @Test
        @DisplayName("lowercase and illegal characters are rejected")
        void malformedCodeRejected() {
            CatalogItemRequestDto request = serviceRequest();
            request.setOperationCode("brake-pad&front");

            assertThatThrownBy(() -> service.addCatalogItem("service", request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("operationCode");
        }

        @Test
        @DisplayName("a code held by another service is rejected before the DB constraint fires")
        void duplicateCodeRejected() {
            ServiceEntity holder = new ServiceEntity();
            holder.setId(OTHER_SERVICE_ID);
            when(serviceRepository.findByOperationCode("BRAKE-PAD-FRONT")).thenReturn(Optional.of(holder));

            CatalogItemRequestDto request = serviceRequest();
            request.setOperationCode("BRAKE-PAD-FRONT");

            assertThatThrownBy(() -> service.addCatalogItem("service", request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("already assigned");
        }

        @Test
        @DisplayName("a service keeps its own code on update without tripping the duplicate check")
        void ownCodeSurvivesUpdate() {
            ServiceEntity existing = new ServiceEntity();
            existing.setId(SERVICE_ID);
            when(serviceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(existing));
            when(serviceRepository.findByOperationCode("BRAKE-PAD-FRONT")).thenReturn(Optional.of(existing));

            CatalogItemRequestDto request = serviceRequest();
            request.setOperationCode("BRAKE-PAD-FRONT");

            CatalogItemResponseDto response =
                    service.updateCatalogItem("service", SERVICE_ID, request).orElseThrow();

            assertThat(response.getOperationCode()).isEqualTo("BRAKE-PAD-FRONT");
        }
    }

    @Nested
    @DisplayName("operationCategory and defaultLaborHours validation")
    class CategoryAndHoursValidation {

        @Test
        @DisplayName("unknown category is rejected")
        void unknownCategoryRejected() {
            CatalogItemRequestDto request = serviceRequest();
            request.setOperationCategory("BODYWORK");

            assertThatThrownBy(() -> service.addCatalogItem("service", request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("operationCategory");
        }

        @Test
        @DisplayName("hours finer than tenths are rejected — book time is tenths of an hour")
        void subTenthHoursRejected() {
            CatalogItemRequestDto request = serviceRequest();
            request.setDefaultLaborHours(new BigDecimal("1.25"));

            assertThatThrownBy(() -> service.addCatalogItem("service", request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("defaultLaborHours");
        }

        @Test
        @DisplayName("zero and negative hours are rejected")
        void nonPositiveHoursRejected() {
            CatalogItemRequestDto request = serviceRequest();
            request.setDefaultLaborHours(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.addCatalogItem("service", request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("defaultLaborHours");
        }

        @Test
        @DisplayName("whole hours normalize to tenths scale")
        void wholeHoursNormalized() {
            CatalogItemRequestDto request = serviceRequest();
            request.setDefaultLaborHours(new BigDecimal("2"));

            CatalogItemResponseDto response = service.addCatalogItem("service", request);

            assertThat(response.getDefaultLaborHours()).isEqualTo(new BigDecimal("2.0"));
        }
    }
}
