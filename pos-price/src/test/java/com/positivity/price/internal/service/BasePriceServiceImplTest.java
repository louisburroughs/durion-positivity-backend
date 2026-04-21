package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.entity.ProductBasePrice;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "java:S100", "java:S1192" })
class BasePriceServiceImplTest {

    @Mock
    private ProductBasePriceRepository repository;

    @InjectMocks
    private BasePriceServiceImpl service;

    @Test
    void saveBasePrice_preservesEffectiveTo_whenEntityAlreadyExists() {
        UUID productId = UUID.randomUUID();
        Instant existingEffectiveTo = Instant.now().plusSeconds(3600);

        ProductBasePrice existing = new ProductBasePrice();
        existing.setProductId(productId);
        existing.setMsrp(BigDecimal.ONE);
        existing.setCurrency("USD");
        existing.setEffectiveFrom(Instant.now().minusSeconds(7200));
        existing.setEffectiveTo(existingEffectiveTo);

        when(repository.findById(productId)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProductBasePrice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveBasePrice(productId, BigDecimal.TEN, "USD", Instant.now());

        ArgumentCaptor<ProductBasePrice> captor = ArgumentCaptor.forClass(ProductBasePrice.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getEffectiveTo()).isEqualTo(existingEffectiveTo);
    }
}
