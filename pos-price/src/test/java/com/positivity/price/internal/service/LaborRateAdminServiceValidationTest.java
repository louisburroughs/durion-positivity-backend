package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.LaborRateAdjustmentRequest;
import com.positivity.price.internal.dto.LaborRateAdjustmentResponse;
import com.positivity.price.internal.dto.LaborRateRequest;
import com.positivity.price.internal.dto.LaborRateResponse;
import com.positivity.price.internal.entity.LaborRate;
import com.positivity.price.internal.entity.LaborRateAdjustment;
import com.positivity.price.internal.enums.LaborRateAdjustmentType;
import com.positivity.price.internal.enums.ServiceOperationCategory;
import com.positivity.price.internal.exception.LaborRateValidationException;
import com.positivity.price.internal.repository.LaborRateAdjustmentRepository;
import com.positivity.price.internal.repository.LaborRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * The omitted-field half of the authoring rules (#1575 Tier 0, T0-3).
 *
 * <p>{@link LaborRateAdminServiceImplTest} covers the fields a caller got <em>wrong</em> — an
 * unknown category, an inverted window, a rate of zero. This covers the ones they left out
 * entirely, plus the two boundaries either test could have been read as owning: a window that
 * ends exactly when it starts, and a blank string, which is a field omitted in a longer way and
 * must not be stored as one.
 *
 * <p>Both matter because the V4 CHECK constraints are only a backstop: a null arriving at the
 * database gives a 500 carrying a constraint name, where the caller needed a 422 naming the
 * field they forgot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LaborRateAdminServiceImpl — omitted and boundary fields")
class LaborRateAdminServiceValidationTest {

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
    @DisplayName("a rate field left out")
    class OmittedRateFields {

        @Test
        @DisplayName("no currency at all is refused for the same reason a two-letter one is")
        void currencyIsRequired() {
            LaborRateRequest request = rateRequest();
            request.setCurrency(null);

            assertThatThrownBy(() -> service.createRate(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("currency");
        }

        @Test
        @DisplayName("a blank currency is an omission, not a value — it is not stored as spaces")
        void blankCurrencyIsRefused() {
            LaborRateRequest request = rateRequest();
            request.setCurrency("   ");

            assertThatThrownBy(() -> service.createRate(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("currency");
        }

        @Test
        @DisplayName("no hourly rate is refused before the database sees a null")
        void hourlyRateIsRequired() {
            LaborRateRequest request = rateRequest();
            request.setHourlyRate(null);

            assertThatThrownBy(() -> service.createRate(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("hourlyRate");
        }

        @Test
        @DisplayName("a rate with no start has no window, so it is refused naming effectiveFrom")
        void effectiveFromIsRequired() {
            LaborRateRequest request = rateRequest();
            request.setEffectiveFrom(null);

            assertThatThrownBy(() -> service.createRate(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("effectiveFrom");
        }

        @Test
        @DisplayName("a blank category is the platform-wide rate, not an unknown vocabulary term")
        void blankCategoryWidensRatherThanFailing() {
            LaborRateRequest request = rateRequest();
            request.setOperationCategory("   ");

            LaborRateResponse response = service.createRate(request);

            assertThat(response.getOperationCategory()).isNull();
        }
    }

    @Nested
    @DisplayName("the effective window boundary")
    class WindowBoundary {

        @Test
        @DisplayName("a window ending exactly when it starts prices nothing, so it is refused")
        void zeroLengthWindowIsRefused() {
            LaborRateRequest request = rateRequest();
            request.setEffectiveTo(FROM);

            assertThatThrownBy(() -> service.createRate(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("effectiveTo must be after effectiveFrom");
        }

        @Test
        @DisplayName("a window that does end after it starts is stored with its end intact")
        void closedWindowIsStored() {
            Instant to = FROM.plusSeconds(86_400);
            LaborRateRequest request = rateRequest();
            request.setEffectiveTo(to);

            LaborRateResponse response = service.createRate(request);

            assertThat(response.getEffectiveFrom()).isEqualTo(FROM);
            assertThat(response.getEffectiveTo()).isEqualTo(to);
        }
    }

    @Nested
    @DisplayName("a matrix step field left out")
    class OmittedStepFields {

        @Test
        @DisplayName("no adjustment code is refused — resolution matches on the code, so it cannot be null")
        void codeIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setAdjustmentCode(null);

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("adjustmentCode is required");
        }

        @Test
        @DisplayName("a blank adjustment code is refused for the same reason as a missing one")
        void blankCodeIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setAdjustmentCode("   ");

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("adjustmentCode is required");
        }

        @Test
        @DisplayName("no adjustment type is refused before the unknown-type message can mislead")
        void typeIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setAdjustmentType(null);

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("adjustmentType is required");
        }

        @Test
        @DisplayName("a blank adjustment type reads as required, not as an unrecognised type")
        void blankTypeIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setAdjustmentType("   ");

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("adjustmentType is required");
        }

        @Test
        @DisplayName("no adjustment value is refused — a step with no magnitude adjusts nothing")
        void valueIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setAdjustmentValue(null);

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("adjustmentValue is required");
        }

        @Test
        @DisplayName("no sequence is refused: order changes the answer, so it cannot be defaulted")
        void sequenceIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setSequence(null);

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("sequence is required");
        }

        @Test
        @DisplayName("a step with no start is refused naming effectiveFrom")
        void stepEffectiveFromIsRequired() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setEffectiveFrom(null);

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(LaborRateValidationException.class)
                    .hasMessageContaining("effectiveFrom is required");
        }

        @Test
        @DisplayName("a blank description is stored as absent, so a quote does not render whitespace")
        void blankDescriptionBecomesNull() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setDescription("   ");

            LaborRateAdjustmentResponse response = service.createAdjustment(request);

            assertThat(response.getDescription()).isNull();
        }

        @Test
        @DisplayName("a step scoped to a category reports that category back")
        void categoryScopedStepReportsItsCategory() {
            LaborRateAdjustmentRequest request = stepRequest();
            request.setOperationCategory("tire_service");

            LaborRateAdjustmentResponse response = service.createAdjustment(request);

            assertThat(response.getOperationCategory()).isEqualTo("TIRE_SERVICE");
            assertThat(response.getAdjustmentCode()).isEqualTo("CORROSION");
            assertThat(response.getAdjustmentType()).isEqualTo("PERCENT");
        }
    }

    @Nested
    @DisplayName("listing what is stored")
    class Listing {

        @Test
        @DisplayName("rates are mapped in the order the repository returns them — newest window first")
        void listsRatesInRepositoryOrder() {
            when(rateRepository.findAllByOrderByEffectiveFromDesc())
                    .thenReturn(List.of(
                            rate(SHOP_A, ServiceOperationCategory.TIRE_SERVICE, "105.00"), rate(null, null, "125.00")));

            List<LaborRateResponse> rates = service.listRates();

            assertThat(rates).hasSize(2);
            assertThat(rates.get(0).getOperationCategory()).isEqualTo("TIRE_SERVICE");
            assertThat(rates.get(0).getLocationId()).isEqualTo(SHOP_A);
            assertThat(rates.get(1).getOperationCategory()).isNull();
            assertThat(rates.get(1).getLocationId()).isNull();
        }

        @Test
        @DisplayName("steps are mapped in the order the repository returns them — sequence, then code")
        void listsAdjustmentsInRepositoryOrder() {
            when(adjustmentRepository.findAllByOrderBySequenceAscAdjustmentCodeAsc())
                    .thenReturn(List.of(step("CORROSION", 10), step("AFTER_HOURS", 20)));

            List<LaborRateAdjustmentResponse> steps = service.listAdjustments();

            assertThat(steps)
                    .extracting(LaborRateAdjustmentResponse::getAdjustmentCode)
                    .containsExactly("CORROSION", "AFTER_HOURS");
            assertThat(steps)
                    .extracting(LaborRateAdjustmentResponse::getSequence)
                    .containsExactly(10, 20);
        }

        @Test
        @DisplayName("nothing stored is an empty list, not a null the caller has to guard")
        void emptyRepositoryIsAnEmptyList() {
            when(rateRepository.findAllByOrderByEffectiveFromDesc()).thenReturn(List.of());
            when(adjustmentRepository.findAllByOrderBySequenceAscAdjustmentCodeAsc())
                    .thenReturn(List.of());

            assertThat(service.listRates()).isEmpty();
            assertThat(service.listAdjustments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("upsert scope matching")
    class UpsertScope {

        @Test
        @DisplayName("the same shop and instant under a different category is a different rate, so it is created")
        void differentCategoryAtTheSameInstantIsANewRate() {
            when(rateRepository.findByEffectiveFrom(FROM))
                    .thenReturn(List.of(rate(SHOP_A, ServiceOperationCategory.REPAIR, "142.00")));

            LaborRateRequest request = rateRequest();
            request.setOperationCategory("TIRE_SERVICE");

            LaborRateResponse response = service.upsertRate(request);

            assertThat(response.getOperationCategory()).isEqualTo("TIRE_SERVICE");
            assertThat(response.getHourlyRate()).isEqualByComparingTo("105.00");
        }

        @Test
        @DisplayName("the same code and instant under a different scope is a different step, so it is created")
        void differentScopeAtTheSameInstantIsANewStep() {
            LaborRateAdjustment held = step("CORROSION", 10);
            held.setLocationId(SHOP_A);
            when(adjustmentRepository.findByAdjustmentCodeAndEffectiveFrom("CORROSION", FROM))
                    .thenReturn(List.of(held));

            LaborRateAdjustmentResponse response = service.upsertAdjustment(stepRequest());

            assertThat(response.getLocationId()).isNull();
            assertThat(response.getAdjustmentCode()).isEqualTo("CORROSION");
        }
    }

    private static LaborRate rate(UUID locationId, ServiceOperationCategory category, String hourly) {
        LaborRate rate = new LaborRate();
        rate.setId(UUID.randomUUID());
        rate.setLocationId(locationId);
        rate.setOperationCategory(category);
        rate.setCurrency("USD");
        rate.setHourlyRate(new BigDecimal(hourly));
        rate.setEffectiveFrom(FROM);
        return rate;
    }

    private static LaborRateAdjustment step(String code, int sequence) {
        LaborRateAdjustment step = new LaborRateAdjustment();
        step.setId(UUID.randomUUID());
        step.setAdjustmentCode(code);
        step.setAdjustmentType(LaborRateAdjustmentType.PERCENT);
        step.setAdjustmentValue(new BigDecimal("15.0"));
        step.setSequence(sequence);
        step.setEffectiveFrom(FROM);
        return step;
    }
}
