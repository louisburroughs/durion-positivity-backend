package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.LaborRateAdjustmentRequest;
import com.positivity.price.internal.dto.LaborRateRequest;
import com.positivity.price.internal.dto.LaborRateResponse;
import com.positivity.price.internal.exception.LaborRateValidationException;
import com.positivity.price.internal.repository.LaborRateAdjustmentRepository;
import com.positivity.price.internal.repository.LaborRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Authoring rules for labor rates and matrix steps (#1575 Tier 0, T0-3): the vocabulary and the
 * effective window are checked here so a caller gets a 422 naming the field, not a 500 carrying
 * a constraint name from the V4 backstop.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborRateAdminServiceImpl")
class LaborRateAdminServiceImplTest {

    private static final UUID SHOP_A = UUID.fromString("0198f2a1-0000-7000-8000-00000000000a");
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private LaborRateRepository rateRepository;

    @Mock
    private LaborRateAdjustmentRepository adjustmentRepository;

    private LaborRateAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LaborRateAdminServiceImpl(rateRepository, adjustmentRepository);
        when(rateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static LaborRateRequest rateRequest() {
        LaborRateRequest request = new LaborRateRequest();
        request.setLocationId(SHOP_A);
        request.setOperationCategory("TIRE_SERVICE");
        request.setCurrency("usd");
        request.setHourlyRate(new BigDecimal("105.00"));
        request.setEffectiveFrom(FROM);
        return request;
    }

    private static LaborRateAdjustmentRequest stepRequest() {
        LaborRateAdjustmentRequest request = new LaborRateAdjustmentRequest();
        request.setAdjustmentCode("corrosion");
        request.setAdjustmentType("percent");
        request.setAdjustmentValue(new BigDecimal("15.0"));
        request.setSequence(10);
        request.setEffectiveFrom(FROM);
        return request;
    }

    @Nested
    @DisplayName("rates")
    class Rates {

        @Test
        @DisplayName("stores the rate with the currency normalized to upper case")
        void storesNormalizedRate() {
            LaborRateResponse response = service.createRate(rateRequest());

            assertThat(response.getCurrency()).isEqualTo("USD");
            assertThat(response.getHourlyRate()).isEqualByComparingTo("105.00");
            assertThat(response.getOperationCategory()).isEqualTo("TIRE_SERVICE");
            assertThat(response.getLocationId()).isEqualTo(SHOP_A);
        }

        @Test
        @DisplayName("omitting location and category stores the platform default rate")
        void platformDefaultRate() {
            LaborRateRequest request = rateRequest();
            request.setLocationId(null);
            request.setOperationCategory(null);

            LaborRateResponse response = service.createRate(request);

            assertThat(response.getLocationId()).isNull();
            assertThat(response.getOperationCategory()).isNull();
        }

        @Test
        @DisplayName("an unknown category is a 422 naming the field, not a constraint violation later")
        void unknownCategoryRejected() {
            LaborRateRequest bad = rateRequest();
            bad.setOperationCategory("BODYWORK");

            assertThatThrownBy(() -> service.createRate(bad))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("operationCategory");
            verify(rateRepository, never()).save(any());
        }

        @Test
        @DisplayName("a zero or negative rate is refused — a free hour is a discount, not a rate")
        void nonPositiveRateRejected() {
            LaborRateRequest bad = rateRequest();
            bad.setHourlyRate(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createRate(bad))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("hourlyRate");
        }

        @Test
        @DisplayName("a window that ends before it starts is refused")
        void invertedWindowRejected() {
            LaborRateRequest bad = rateRequest();
            bad.setEffectiveTo(FROM.minusSeconds(1));

            assertThatThrownBy(() -> service.createRate(bad))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("effectiveTo");
        }

        @Test
        @DisplayName("a currency that is not three letters is refused")
        void badCurrencyRejected() {
            LaborRateRequest bad = rateRequest();
            bad.setCurrency("DOLLARS");

            assertThatThrownBy(() -> service.createRate(bad))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("currency");
        }
    }

    @Nested
    @DisplayName("matrix steps")
    class Steps {

        @Test
        @DisplayName("the code is uppercased on the way in, because resolution matches uppercased codes")
        void codeIsUppercased() {
            assertThat(service.createAdjustment(stepRequest()).getAdjustmentCode())
                    .isEqualTo("CORROSION");
        }

        @Test
        @DisplayName("the type is parsed case-insensitively and stored canonically")
        void typeIsNormalized() {
            assertThat(service.createAdjustment(stepRequest()).getAdjustmentType())
                    .isEqualTo("PERCENT");
        }

        @Test
        @DisplayName("a negative value is allowed — that is how a contract discount is expressed")
        void negativeValueAllowed() {
            LaborRateAdjustmentRequest discount = stepRequest();
            discount.setAdjustmentCode("FLEET_CONTRACT");
            discount.setAdjustmentValue(new BigDecimal("-10.0"));

            assertThat(service.createAdjustment(discount).getAdjustmentValue()).isEqualByComparingTo("-10.0");
        }

        @Test
        @DisplayName("an unknown adjustment type is refused")
        void unknownTypeRejected() {
            LaborRateAdjustmentRequest bad = stepRequest();
            bad.setAdjustmentType("MULTIPLIER");

            assertThatThrownBy(() -> service.createAdjustment(bad))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("adjustmentType");
            verify(adjustmentRepository, never()).save(any());
        }
    }
}
