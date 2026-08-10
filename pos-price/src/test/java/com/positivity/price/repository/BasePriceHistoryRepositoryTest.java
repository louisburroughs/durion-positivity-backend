package com.positivity.price.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.price.internal.exception.BasePriceWindowConflictException;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import com.positivity.price.service.BasePriceService;
import com.positivity.price.service.model.BasePriceView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Base price history-retaining append-on-change behavior")
class BasePriceHistoryRepositoryTest {

    private static final Instant T1 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2025-06-01T00:00:00Z");

    @Autowired
    private BasePriceService basePriceService;

    @Autowired
    private ProductBasePriceRepository repository;

    @Test
    @DisplayName("price change appends a row and closes the predecessor's window; history is queryable")
    void priceChangeAppendsRowAndClosesPredecessor() {
        UUID productId = UUID.randomUUID();

        UUID firstRowId = basePriceService.saveBasePrice(productId, new BigDecimal("100.0000"), "USD", T1);
        UUID secondRowId = basePriceService.saveBasePrice(productId, new BigDecimal("120.0000"), "USD", T2);

        assertNotEquals(firstRowId, secondRowId);

        List<BasePriceView> history = basePriceService.getBasePriceHistory(productId);
        assertEquals(2, history.size());
        assertEquals(secondRowId, history.get(0).id());
        assertEquals(T2, history.get(1).effectiveTo());
    }

    @Test
    @DisplayName("time-travel query answers what the base price was at time T with half-open boundaries")
    void timeTravelQueryUsesHalfOpenBoundaries() {
        UUID productId = UUID.randomUUID();
        basePriceService.saveBasePrice(productId, new BigDecimal("100.0000"), "USD", T1);
        basePriceService.saveBasePrice(productId, new BigDecimal("120.0000"), "USD", T2);

        BasePriceView beforeCutover =
                basePriceService.getBasePriceAt(productId, T2.minusSeconds(1)).orElseThrow();
        BasePriceView atCutover = basePriceService.getBasePriceAt(productId, T2).orElseThrow();

        assertEquals(new BigDecimal("100.0000"), beforeCutover.msrp());
        assertEquals(new BigDecimal("120.0000"), atCutover.msrp());
        assertTrue(
                basePriceService.getBasePriceAt(productId, T1.minusSeconds(1)).isEmpty());
    }

    @Test
    @DisplayName("backdated write is rejected and identical re-ingest is an idempotent no-op")
    void backdatedWriteRejectedAndReIngestIsNoOp() {
        UUID productId = UUID.randomUUID();
        UUID firstRowId = basePriceService.saveBasePrice(productId, new BigDecimal("100.0000"), "USD", T2);

        assertThrows(
                BasePriceWindowConflictException.class,
                () -> basePriceService.saveBasePrice(productId, new BigDecimal("90.0000"), "USD", T1));

        UUID replayedRowId = basePriceService.saveBasePrice(productId, new BigDecimal("100.0000"), "USD", T2);
        assertEquals(firstRowId, replayedRowId);
        assertEquals(
                1, repository.findByProductIdOrderByEffectiveFromDesc(productId).size());
    }
}
