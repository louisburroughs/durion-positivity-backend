package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ServiceLaborStandardRequestDto;
import com.positivity.catalog.internal.dto.ServiceLaborStandardResponseDto;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Authoring rules for DURION-source labor standards (#1569): provenance is stamped by the
 * server, corrections supersede rather than update, imported rows are untouchable, and no two
 * active rows may answer the same vehicle key and time type.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServiceLaborStandardServiceImpl")
class ServiceLaborStandardServiceImplTest {

    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9c01");
    private static final UUID STANDARD_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9c02");

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private ServiceLaborStandardRepository laborStandardRepository;

    private ServiceLaborStandardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ServiceLaborStandardServiceImpl(
                serviceRepository,
                laborStandardRepository,
                Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC));
        when(serviceRepository.existsById(SERVICE_ID)).thenReturn(true);
        when(laborStandardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(laborStandardRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                .thenReturn(List.of());
    }

    private static ServiceLaborStandardRequestDto request() {
        ServiceLaborStandardRequestDto request = new ServiceLaborStandardRequestDto();
        request.setMake("Honda");
        request.setModel("Civic");
        request.setVehicleYear("2019-2023");
        request.setLaborHours(new BigDecimal("1.5"));
        return request;
    }

    private static ServiceLaborStandardEntity activeDurionRow() {
        ServiceLaborStandardEntity row = new ServiceLaborStandardEntity();
        row.setId(STANDARD_ID);
        row.setServiceId(SERVICE_ID);
        row.setMake("Honda");
        row.setModel("Civic");
        row.setVehicleYear("2019-2023");
        row.setLaborHours(new BigDecimal("1.5"));
        row.setTimeType(LaborTimeType.DURION_STANDARD);
        row.setSourceCode("DURION");
        row.setSourceRevision("2026-08-01T00:00:00Z");
        return row;
    }

    @Nested
    @DisplayName("shop ownership (#1575 Tier 0)")
    class ShopOwnership {

        private static final UUID SHOP_A = UUID.fromString("0198f2a1-0000-7000-8000-00000000000a");
        private static final UUID SHOP_B = UUID.fromString("0198f2a1-0000-7000-8000-00000000000b");

        @Test
        @DisplayName("defaults to PLATFORM with no owning location when the request says nothing")
        void defaultsToPlatform() {
            ServiceLaborStandardResponseDto response = service.create(SERVICE_ID, request());

            assertThat(response.getOwnerScope()).isEqualTo("PLATFORM");
            assertThat(response.getOwnerLocationId()).isNull();
        }

        @Test
        @DisplayName("a SHOP row records its owning location and stays DURION-sourced")
        void shopRowRecordsItsOwner() {
            ServiceLaborStandardRequestDto shopRequest = request();
            shopRequest.setOwnerScope("SHOP");
            shopRequest.setOwnerLocationId(SHOP_A);

            ServiceLaborStandardResponseDto response = service.create(SERVICE_ID, shopRequest);

            assertThat(response.getOwnerScope()).isEqualTo("SHOP");
            assertThat(response.getOwnerLocationId()).isEqualTo(SHOP_A);
            assertThat(response.getSourceCode()).isEqualTo("DURION");
        }

        @Test
        @DisplayName("SHOP without a location is refused — such a row would resolve for nobody")
        void shopWithoutLocationRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setOwnerScope("SHOP");

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("ownerLocationId is required");
            verify(laborStandardRepository, never()).save(any());
        }

        @Test
        @DisplayName("PLATFORM with a location is refused — the row would lie about its reach")
        void platformWithLocationRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setOwnerLocationId(SHOP_A);

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("must be omitted");
        }

        @Test
        @DisplayName("an unknown ownerScope is a validation error, not a silent PLATFORM default")
        void unknownScopeRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setOwnerScope("REGION");

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("ownerScope");
        }

        @Test
        @DisplayName("two shops may hold their own time for the same vehicle key — that is not a duplicate")
        void differentShopsCoexistOnTheSameKey() {
            ServiceLaborStandardEntity shopBRow = activeDurionRow();
            shopBRow.setOwnerScope(LaborStandardOwnerScope.SHOP);
            shopBRow.setOwnerLocationId(SHOP_B);
            when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(shopBRow));

            ServiceLaborStandardRequestDto shopARequest = request();
            shopARequest.setOwnerScope("SHOP");
            shopARequest.setOwnerLocationId(SHOP_A);

            assertThat(service.create(SERVICE_ID, shopARequest).getOwnerLocationId())
                    .isEqualTo(SHOP_A);
        }

        @Test
        @DisplayName("but one shop may not hold two active times for the same vehicle key")
        void sameShopDuplicateRejected() {
            ServiceLaborStandardEntity shopARow = activeDurionRow();
            shopARow.setOwnerScope(LaborStandardOwnerScope.SHOP);
            shopARow.setOwnerLocationId(SHOP_A);
            when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(shopARow));

            ServiceLaborStandardRequestDto shopARequest = request();
            shopARequest.setOwnerScope("SHOP");
            shopARequest.setOwnerLocationId(SHOP_A);

            assertThatThrownBy(() -> service.create(SERVICE_ID, shopARequest))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("supersede");
        }

        @Test
        @DisplayName("a shop row does not collide with the platform row on the same vehicle key")
        void shopRowDoesNotCollideWithPlatformRow() {
            when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(activeDurionRow()));

            ServiceLaborStandardRequestDto shopRequest = request();
            shopRequest.setOwnerScope("SHOP");
            shopRequest.setOwnerLocationId(SHOP_A);

            assertThat(service.create(SERVICE_ID, shopRequest).getOwnerScope()).isEqualTo("SHOP");
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("stamps DURION provenance and defaults the time type")
        void stampsProvenance() {
            ServiceLaborStandardResponseDto response = service.create(SERVICE_ID, request());

            assertThat(response.getSourceCode()).isEqualTo("DURION");
            assertThat(response.getSourceRevision()).isNotBlank();
            assertThat(response.getTimeType()).isEqualTo("DURION_STANDARD");
            assertThat(response.getLaborHours()).isEqualByComparingTo("1.5");
            assertThat(response.getServiceId()).isEqualTo(SERVICE_ID);
            assertThat(response.getSupersededAt()).isNull();
        }

        @Test
        @DisplayName("unknown service answers 404, not a row pointing nowhere")
        void unknownServiceRejected() {
            when(serviceRepository.existsById(SERVICE_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.create(SERVICE_ID, request()))
                    .isInstanceOf(CatalogNotFoundException.class);
            verify(laborStandardRepository, never()).save(any());
        }

        @Test
        @DisplayName("missing hours are rejected — an estimate row without a time is nothing")
        void missingHoursRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setLaborHours(null);

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("laborHours");
        }

        @Test
        @DisplayName("hours finer than tenths are rejected")
        void subTenthHoursRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setLaborHours(new BigDecimal("1.25"));

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("laborHours");
        }

        @Test
        @DisplayName("unknown time type is rejected")
        void unknownTimeTypeRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setTimeType("GUESSED");

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("timeType");
        }

        @Test
        @DisplayName("malformed includedOpCodes entries are rejected")
        void malformedIncludedOpCodeRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setIncludedOpCodes(List.of("brake pad front"));

            assertThatThrownBy(() -> service.create(SERVICE_ID, bad))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("includedOpCodes");
        }

        @Test
        @DisplayName("a second active row for the same vehicle key and type is refused as a duplicate")
        void duplicateActiveRowRejected() {
            when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(activeDurionRow()));

            assertThatThrownBy(() -> service.create(SERVICE_ID, request()))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("supersede");
            verify(laborStandardRepository, never()).save(any());
        }

        @Test
        @DisplayName("an all-null vehicle key is still a key: two active wildcard rows are a duplicate")
        void wildcardKeyDuplicateRejected() {
            ServiceLaborStandardEntity wildcard = activeDurionRow();
            wildcard.setMake(null);
            wildcard.setModel(null);
            wildcard.setVehicleYear(null);
            when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(wildcard));
            ServiceLaborStandardRequestDto wildcardRequest = new ServiceLaborStandardRequestDto();
            wildcardRequest.setLaborHours(new BigDecimal("2.0"));

            assertThatThrownBy(() -> service.create(SERVICE_ID, wildcardRequest))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("supersede");
        }

        @Test
        @DisplayName("blank vehicle-key strings are stored as null — the wildcard convention the V18 index relies on")
        void blankStringsNormalizeToNull() {
            ServiceLaborStandardRequestDto blanks = request();
            blanks.setMake("  ");
            blanks.setModel("");
            blanks.setSubmodel(" ");
            blanks.setEngineCode("");
            blanks.setOverlapGroup("  ");

            ServiceLaborStandardResponseDto response = service.create(SERVICE_ID, blanks);

            assertThat(response.getMake()).isNull();
            assertThat(response.getModel()).isNull();
            assertThat(response.getSubmodel()).isNull();
            assertThat(response.getEngineCode()).isNull();
            assertThat(response.getOverlapGroup()).isNull();
        }

        @Test
        @DisplayName("the same vehicle key with a different time type coexists — warranty beside retail")
        void differentTimeTypeCoexists() {
            when(laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(activeDurionRow()));
            ServiceLaborStandardRequestDto warranty = request();
            warranty.setTimeType("OEM_WARRANTY");

            ServiceLaborStandardResponseDto response = service.create(SERVICE_ID, warranty);

            assertThat(response.getTimeType()).isEqualTo("OEM_WARRANTY");
        }
    }

    @Nested
    @DisplayName("list")
    class ListStandards {

        @Test
        @DisplayName("active only by default; superseded rows appear only when asked for")
        void filtersSupersededByDefault() {
            ServiceLaborStandardEntity superseded = activeDurionRow();
            superseded.setSupersededAt(Instant.parse("2026-08-15T00:00:00Z"));
            when(laborStandardRepository.findByServiceIdOrderByCreatedAtAsc(SERVICE_ID))
                    .thenReturn(List.of(activeDurionRow(), superseded));

            assertThat(service.list(SERVICE_ID, false)).isEmpty();
            assertThat(service.list(SERVICE_ID, true)).hasSize(2);
        }
    }

    /**
     * The vehicle-key columns are varchar(16)/varchar(64) in V18; over-length values must be
     * refused as 400 by bean validation at the controller boundary, never surface as a
     * database error (#1631 review F1).
     */
    @Nested
    @DisplayName("request bean validation")
    class RequestBeanValidation {

        private final jakarta.validation.Validator validator =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        @Test
        @DisplayName("over-length vehicle-key fields violate @Size")
        void overLengthFieldsRejected() {
            ServiceLaborStandardRequestDto bad = request();
            bad.setVehicleYear("2019-2023 facelift");
            bad.setMake("M".repeat(65));
            bad.setOverlapGroup("G".repeat(65));

            assertThat(validator.validate(bad))
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("vehicleYear", "make", "overlapGroup");
        }

        @Test
        @DisplayName("column-width values pass")
        void maxWidthValuesPass() {
            ServiceLaborStandardRequestDto ok = request();
            ok.setVehicleYear("2019-2023");
            ok.setMake("M".repeat(64));

            assertThat(validator.validate(ok)).isEmpty();
        }
    }

    @Nested
    @DisplayName("supersede")
    class Supersede {

        @Test
        @DisplayName("marks the old row superseded and returns the replacement with fresh provenance")
        void supersedesAndReplaces() {
            ServiceLaborStandardEntity existing = activeDurionRow();
            when(laborStandardRepository.findById(STANDARD_ID)).thenReturn(Optional.of(existing));
            ServiceLaborStandardRequestDto correction = request();
            correction.setLaborHours(new BigDecimal("1.8"));

            ServiceLaborStandardResponseDto response = service.supersede(SERVICE_ID, STANDARD_ID, correction);

            assertThat(existing.getSupersededAt()).isNotNull();
            assertThat(response.getLaborHours()).isEqualByComparingTo("1.8");
            assertThat(response.getSupersededAt()).isNull();
            // The old row must be flushed as superseded before the replacement is inserted, or
            // the V18 active-key unique index rejects the replacement (inserts flush first).
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(laborStandardRepository);
            inOrder.verify(laborStandardRepository).saveAndFlush(existing);
            ArgumentCaptor<ServiceLaborStandardEntity> saved =
                    ArgumentCaptor.forClass(ServiceLaborStandardEntity.class);
            inOrder.verify(laborStandardRepository).save(saved.capture());
            assertThat(saved.getValue()).isNotSameAs(existing);
        }

        @Test
        @DisplayName("a standard belonging to another service is 404, not someone else's row edited")
        void wrongServiceIs404() {
            ServiceLaborStandardEntity existing = activeDurionRow();
            existing.setServiceId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f9c99"));
            when(laborStandardRepository.findById(STANDARD_ID)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.supersede(SERVICE_ID, STANDARD_ID, request()))
                    .isInstanceOf(CatalogNotFoundException.class);
        }

        @Test
        @DisplayName("an already-superseded row cannot be superseded again")
        void alreadySupersededRejected() {
            ServiceLaborStandardEntity existing = activeDurionRow();
            existing.setSupersededAt(Instant.parse("2026-08-15T00:00:00Z"));
            when(laborStandardRepository.findById(STANDARD_ID)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.supersede(SERVICE_ID, STANDARD_ID, request()))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("already superseded");
        }

        @Test
        @DisplayName("imported rows are refused — vendor data is corrected by the vendor's next import")
        void importedRowRefused() {
            ServiceLaborStandardEntity existing = activeDurionRow();
            existing.setSourceCode("MOCKGUIDE");
            when(laborStandardRepository.findById(STANDARD_ID)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.supersede(SERVICE_ID, STANDARD_ID, request()))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("MOCKGUIDE");
        }
    }
}
