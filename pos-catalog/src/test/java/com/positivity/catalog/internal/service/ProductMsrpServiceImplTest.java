package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.CreateMsrpRequestDto;
import com.positivity.catalog.internal.dto.ProductMsrpDto;
import com.positivity.catalog.internal.dto.UpdateMsrpRequestDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductMsrpRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Manufacturer suggested retail prices. A product may carry at most one open-ended MSRP, windows
 * may not overlap, and a window that has already closed is immutable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductMsrpServiceImpl")
class ProductMsrpServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb01");
    private static final UUID MSRP_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb02");
    private static final UUID USER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb03");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @Mock
    private ProductMsrpRepository productMsrpRepository;

    @Mock
    private ProductRepository productRepository;

    private ProductMsrpServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new ProductMsrpServiceImpl(Clock.fixed(NOW, ZoneOffset.UTC), productMsrpRepository, productRepository);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(productMsrpRepository.findOverlapping(any(), any(), any(), any())).thenReturn(List.of());
        when(productMsrpRepository.save(any())).thenAnswer(invocation -> {
            ProductMsrpEntity entity = invocation.getArgument(0);
            if (entity.getMsrpId() == null) {
                entity.setMsrpId(MSRP_ID);
            }
            return entity;
        });
    }

    private static ProductEntity product() {
        ProductEntity product = new ProductEntity();
        product.setId(PRODUCT_ID);
        product.setName("Tire 205/55R16");
        return product;
    }

    private static ProductMsrpEntity msrp(LocalDate startDate, LocalDate endDate, Long version) {
        ProductMsrpEntity entity = new ProductMsrpEntity();
        entity.setMsrpId(MSRP_ID);
        entity.setProduct(product());
        entity.setAmount(new BigDecimal("149.9900"));
        entity.setCurrency("USD");
        entity.setEffectiveStartDate(startDate);
        entity.setEffectiveEndDate(endDate);
        entity.setVersion(version);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(USER_ID);
        return entity;
    }

    private static CreateMsrpRequestDto createRequest(LocalDate startDate, LocalDate endDate) {
        CreateMsrpRequestDto request = new CreateMsrpRequestDto();
        request.setAmount(new BigDecimal("149.99"));
        request.setCurrency("usd");
        request.setEffectiveStartDate(startDate);
        request.setEffectiveEndDate(endDate);
        request.setCreatedByUserId(USER_ID);
        return request;
    }

    private static UpdateMsrpRequestDto updateRequest(LocalDate startDate, LocalDate endDate, Long version) {
        UpdateMsrpRequestDto request = new UpdateMsrpRequestDto();
        request.setAmount(new BigDecimal("159.99"));
        request.setCurrency("USD");
        request.setEffectiveStartDate(startDate);
        request.setEffectiveEndDate(endDate);
        request.setCreatedByUserId(USER_ID);
        request.setVersion(version);
        return request;
    }

    @Nested
    @DisplayName("createMsrp")
    class CreateMsrp {

        @Test
        void storesTheAmountAtFourDecimalsAndUppercasesTheCurrency() {
            ProductMsrpDto response = service.createMsrp(PRODUCT_ID, createRequest(TODAY, null));

            ArgumentCaptor<ProductMsrpEntity> saved = ArgumentCaptor.forClass(ProductMsrpEntity.class);
            verify(productMsrpRepository).save(saved.capture());
            assertThat(saved.getValue().getAmount()).isEqualByComparingTo("149.9900");
            assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
            assertThat(saved.getValue().getUpdatedBy()).isEqualTo(USER_ID);
            assertThat(response.getMsrpId()).isEqualTo(MSRP_ID);
            assertThat(response.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(response.getAmount()).isEqualTo("149.9900");
        }

        @Test
        void refusesASecondOpenEndedRecordForTheSameProduct() {
            when(productMsrpRepository.countByProduct_IdAndEffectiveEndDateIsNull(PRODUCT_ID))
                    .thenReturn(1L);
            CreateMsrpRequestDto request = createRequest(TODAY, null);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("Only one open-ended MSRP record");
        }

        @Test
        void allowsAClosedWindowEvenWhenAnOpenEndedRecordExists() {
            when(productMsrpRepository.countByProduct_IdAndEffectiveEndDateIsNull(PRODUCT_ID))
                    .thenReturn(1L);

            assertThat(service.createMsrp(PRODUCT_ID, createRequest(TODAY, TODAY.plusDays(30))))
                    .isNotNull();
        }

        @Test
        void refusesAWindowThatOverlapsAnExistingOne() {
            when(productMsrpRepository.findOverlapping(eq(PRODUCT_ID), any(), any(), eq(null)))
                    .thenReturn(List.of(msrp(TODAY, null, 0L)));
            CreateMsrpRequestDto request = createRequest(TODAY, null);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("overlap");
        }

        @Test
        void failsForAnUnknownProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());
            CreateMsrpRequestDto request = createRequest(TODAY, null);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("Product not found");
        }

        @Test
        void requiresAStartDate() {
            CreateMsrpRequestDto request = createRequest(null, null);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("effectiveStartDate is required");
        }

        @Test
        void refusesAnEndDateBeforeTheStartDate() {
            CreateMsrpRequestDto request = createRequest(TODAY, TODAY.minusDays(1));

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("effectiveEndDate must be on or after");
        }

        @Test
        void requiresAPositiveAmount() {
            CreateMsrpRequestDto request = createRequest(TODAY, null);
            request.setAmount(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("amount must be positive");
        }

        @Test
        void requiresAnAmount() {
            CreateMsrpRequestDto request = createRequest(TODAY, null);
            request.setAmount(null);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("amount must be positive");
        }

        @Test
        void requiresAThreeLetterCurrencyCode() {
            CreateMsrpRequestDto request = createRequest(TODAY, null);
            request.setCurrency("DOLLARS");

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("3-letter ISO code");
        }

        @Test
        void requiresACurrency() {
            CreateMsrpRequestDto request = createRequest(TODAY, null);
            request.setCurrency("   ");

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("3-letter ISO code");
        }

        @Test
        void requiresANonNullCurrency() {
            CreateMsrpRequestDto request = createRequest(TODAY, null);
            request.setCurrency(null);

            assertThatThrownBy(() -> service.createMsrp(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("3-letter ISO code");
        }
    }

    @Nested
    @DisplayName("updateMsrp")
    class UpdateMsrp {

        @Test
        void appliesTheNewWindowAndAmount() {
            ProductMsrpEntity existing = msrp(TODAY, null, 2L);
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(existing));
            when(productMsrpRepository.countByProduct_IdAndEffectiveEndDateIsNull(PRODUCT_ID))
                    .thenReturn(1L);

            ProductMsrpDto response = service.updateMsrp(PRODUCT_ID, MSRP_ID, updateRequest(TODAY, null, 2L));

            assertThat(existing.getAmount()).isEqualByComparingTo("159.9900");
            assertThat(response.getAmount()).isEqualTo("159.9900");
            assertThat(response.getCurrency()).isEqualTo("USD");
        }

        @Test
        void failsWhenTheRecordDoesNotExist() {
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.empty());
            UpdateMsrpRequestDto request = updateRequest(TODAY, null, null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("MSRP record not found");
        }

        @Test
        void failsWhenTheRecordBelongsToAnotherProduct() {
            ProductMsrpEntity foreign = msrp(TODAY, null, 0L);
            ProductEntity otherProduct = new ProductEntity();
            otherProduct.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb09"));
            foreign.setProduct(otherProduct);
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(foreign));
            UpdateMsrpRequestDto request = updateRequest(TODAY, null, null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("not found for product");
        }

        @Test
        void failsWhenTheRecordHasNoProductAtAll() {
            ProductMsrpEntity orphaned = msrp(TODAY, null, 0L);
            orphaned.setProduct(null);
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(orphaned));
            UpdateMsrpRequestDto request = updateRequest(TODAY, null, null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class);
        }

        @Test
        void refusesToEditAWindowThatHasAlreadyClosed() {
            when(productMsrpRepository.findById(MSRP_ID))
                    .thenReturn(Optional.of(msrp(TODAY.minusDays(30), TODAY.minusDays(1), 0L)));
            UpdateMsrpRequestDto request = updateRequest(TODAY, null, null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("Historical MSRP records are immutable");
        }

        @Test
        void allowsEditingAWindowThatEndsToday() {
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(msrp(TODAY.minusDays(30), TODAY, 0L)));

            assertThat(service.updateMsrp(PRODUCT_ID, MSRP_ID, updateRequest(TODAY.minusDays(30), TODAY, null)))
                    .isNotNull();
        }

        @Test
        void failsOnAStaleVersion() {
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(msrp(TODAY, null, 5L)));
            UpdateMsrpRequestDto request = updateRequest(TODAY, null, 4L);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(OptimisticLockException.class);
        }

        @Test
        void skipsTheVersionCheckWhenTheCallerSuppliesNone() {
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(msrp(TODAY, TODAY.plusDays(1), 5L)));

            assertThat(service.updateMsrp(PRODUCT_ID, MSRP_ID, updateRequest(TODAY, TODAY.plusDays(1), null)))
                    .isNotNull();
        }

        @Test
        void refusesToOpenASecondOpenEndedWindow() {
            // This record is closed today and another record is already open-ended, so removing
            // this one's end date would leave two open-ended windows.
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(msrp(TODAY, TODAY.plusDays(5), 0L)));
            when(productMsrpRepository.countByProduct_IdAndEffectiveEndDateIsNull(PRODUCT_ID))
                    .thenReturn(1L);
            UpdateMsrpRequestDto request = updateRequest(TODAY, null, null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("Only one open-ended MSRP record");
        }

        @Test
        void letsAnAlreadyOpenEndedRecordStayOpenEnded() {
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(msrp(TODAY, null, 0L)));
            when(productMsrpRepository.countByProduct_IdAndEffectiveEndDateIsNull(PRODUCT_ID))
                    .thenReturn(1L);

            assertThat(service.updateMsrp(PRODUCT_ID, MSRP_ID, updateRequest(TODAY, null, null)))
                    .isNotNull();
        }

        @Test
        void refusesAWindowThatWouldOverlapAnotherRecord() {
            when(productMsrpRepository.findById(MSRP_ID)).thenReturn(Optional.of(msrp(TODAY, null, 0L)));
            when(productMsrpRepository.findOverlapping(eq(PRODUCT_ID), any(), any(), eq(MSRP_ID)))
                    .thenReturn(List.of(msrp(TODAY, null, 0L)));
            UpdateMsrpRequestDto request = updateRequest(TODAY, TODAY.plusDays(1), null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogBusinessRuleException.class);
        }

        @Test
        void validatesTheRequestBeforeLoadingAnything() {
            UpdateMsrpRequestDto request = updateRequest(null, null, null);

            assertThatThrownBy(() -> service.updateMsrp(PRODUCT_ID, MSRP_ID, request))
                    .isInstanceOf(CatalogValidationException.class);
            verify(productMsrpRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void servesTheMsrpActiveOnTheGivenDate() {
            when(productMsrpRepository.findActive(PRODUCT_ID, TODAY)).thenReturn(Optional.of(msrp(TODAY, null, 0L)));

            Optional<ProductMsrpDto> active = service.getActiveMsrp(PRODUCT_ID, TODAY);

            assertThat(active).isPresent();
            assertThat(active.get().getEffectiveStartDate()).isEqualTo(TODAY);
            assertThat(active.get().getCreatedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        }

        @Test
        void returnsEmptyWhenNoMsrpCoversTheDate() {
            when(productMsrpRepository.findActive(PRODUCT_ID, TODAY)).thenReturn(Optional.empty());

            assertThat(service.getActiveMsrp(PRODUCT_ID, TODAY)).isEmpty();
        }

        @Test
        void listsEveryMsrpNewestWindowFirst() {
            when(productMsrpRepository.findByProduct_IdOrderByEffectiveStartDateDesc(PRODUCT_ID))
                    .thenReturn(List.of(msrp(TODAY, null, 0L)));

            assertThat(service.getAllMsrp(PRODUCT_ID)).hasSize(1);
        }

        @Test
        void leavesTimestampsUnsetWhenTheRecordHasNone() {
            ProductMsrpEntity fresh = msrp(TODAY, null, 0L);
            fresh.setCreatedAt(null);
            fresh.setUpdatedAt(null);
            when(productMsrpRepository.findByProduct_IdOrderByEffectiveStartDateDesc(PRODUCT_ID))
                    .thenReturn(List.of(fresh));

            ProductMsrpDto dto = service.getAllMsrp(PRODUCT_ID).get(0);

            assertThat(dto.getCreatedAt()).isNull();
            assertThat(dto.getUpdatedAt()).isNull();
        }

        @Test
        void failsForAnUnknownProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAllMsrp(PRODUCT_ID)).isInstanceOf(CatalogNotFoundException.class);
            assertThatThrownBy(() -> service.getActiveMsrp(PRODUCT_ID, TODAY))
                    .isInstanceOf(CatalogNotFoundException.class);
        }
    }
}
