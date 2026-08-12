package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.entity.ProductBasePrice;
import com.positivity.price.internal.exception.BasePriceWindowConflictException;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class BasePriceServiceImplTest {

    private static final Instant T1 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-06-01T00:00:00Z");

    @Mock
    private ProductBasePriceRepository repository;

    @InjectMocks
    private BasePriceServiceImpl service;

    private void stubSaveEcho() {
        when(repository.save(any(ProductBasePrice.class))).thenAnswer(inv -> {
            ProductBasePrice entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });
    }

    private ProductBasePrice openWindow(UUID productId, BigDecimal msrp, Instant from) {
        ProductBasePrice entity = new ProductBasePrice();
        entity.setId(UUID.randomUUID());
        entity.setProductId(productId);
        entity.setMsrp(msrp);
        entity.setCurrency("USD");
        entity.setEffectiveFrom(from);
        entity.setEffectiveTo(null);
        return entity;
    }

    @Test
    void saveBasePrice_appendsOpenWindow_whenNoExistingRows() {
        UUID productId = UUID.randomUUID();
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.empty());
        stubSaveEcho();

        UUID rowId = service.saveBasePrice(productId, BigDecimal.TEN, "USD", T1);

        ArgumentCaptor<ProductBasePrice> captor = ArgumentCaptor.forClass(ProductBasePrice.class);
        verify(repository).save(captor.capture());
        ProductBasePrice saved = captor.getValue();
        assertThat(saved.getProductId()).isEqualTo(productId);
        assertThat(saved.getEffectiveFrom()).isEqualTo(T1);
        assertThat(saved.getEffectiveTo()).isNull();
        assertThat(rowId).isEqualTo(saved.getId());
    }

    @Test
    void saveBasePrice_closesPriorOpenWindow_andAppendsNewRow() {
        UUID productId = UUID.randomUUID();
        ProductBasePrice current = openWindow(productId, BigDecimal.TEN, T1);
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.of(current));
        stubSaveEcho();

        UUID rowId = service.saveBasePrice(productId, BigDecimal.ONE, "USD", T2);

        ArgumentCaptor<ProductBasePrice> captor = ArgumentCaptor.forClass(ProductBasePrice.class);
        verify(repository, times(2)).save(captor.capture());
        List<ProductBasePrice> saves = captor.getAllValues();
        assertThat(saves.get(0).getId()).isEqualTo(current.getId());
        assertThat(saves.get(0).getEffectiveTo()).isEqualTo(T2);
        assertThat(saves.get(1).getEffectiveFrom()).isEqualTo(T2);
        assertThat(saves.get(1).getEffectiveTo()).isNull();
        assertThat(saves.get(1).getMsrp()).isEqualTo(BigDecimal.ONE);
        assertThat(rowId).isEqualTo(saves.get(1).getId());
    }

    @Test
    void saveBasePrice_isIdempotentNoOp_whenOpenWindowHasSameMsrp() {
        UUID productId = UUID.randomUUID();
        ProductBasePrice current = openWindow(productId, new BigDecimal("10.0000"), T1);
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.of(current));

        UUID rowId = service.saveBasePrice(productId, new BigDecimal("10.00"), "USD", T1);

        verify(repository, never()).save(any());
        assertThat(rowId).isEqualTo(current.getId());
    }

    @Test
    void saveBasePrice_rejectsBackdatedEffectiveFrom() {
        UUID productId = UUID.randomUUID();
        ProductBasePrice current = openWindow(productId, BigDecimal.TEN, T2);
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.saveBasePrice(productId, BigDecimal.ONE, "USD", T1))
                .isInstanceOf(BasePriceWindowConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void saveBasePrice_rejectsEffectiveFromEqualToLatestWindowStart() {
        UUID productId = UUID.randomUUID();
        ProductBasePrice current = openWindow(productId, BigDecimal.TEN, T1);
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.saveBasePrice(productId, BigDecimal.ONE, "USD", T1))
                .isInstanceOf(BasePriceWindowConflictException.class);
    }

    @Test
    void saveBasePrice_rejectsOverlapIntoClosedLatestWindow() {
        UUID productId = UUID.randomUUID();
        ProductBasePrice current = openWindow(productId, BigDecimal.TEN, T1);
        current.setEffectiveTo(T2);
        Instant inside = Instant.parse("2025-03-01T00:00:00Z");
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.saveBasePrice(productId, BigDecimal.ONE, "USD", inside))
                .isInstanceOf(BasePriceWindowConflictException.class);
    }

    @Test
    void saveBasePrice_appendsAfterClosedLatestWindow_withoutTouchingIt() {
        UUID productId = UUID.randomUUID();
        ProductBasePrice current = openWindow(productId, BigDecimal.TEN, T1);
        current.setEffectiveTo(T2);
        when(repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, "USD"))
                .thenReturn(Optional.of(current));
        stubSaveEcho();

        service.saveBasePrice(productId, BigDecimal.ONE, "USD", T2);

        ArgumentCaptor<ProductBasePrice> captor = ArgumentCaptor.forClass(ProductBasePrice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNotEqualTo(current.getId());
        assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(T2);
        assertThat(current.getEffectiveTo()).isEqualTo(T2);
    }
}
