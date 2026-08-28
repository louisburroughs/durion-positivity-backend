package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Test-mode provider (story T6): wraps the T1 {@link TestModeTaxCalculator} for
 * estimate/refund. Commit and void are no-ops that always succeed — the
 * lifecycle log row is written by {@link TaxProviderLifecycleService}, which owns
 * persistence so idempotency, PENDING_COMMIT recording and the re-commit job share a
 * single writer. This provider never fails.
 */
@Component
public class TestModeTaxProvider implements TaxProviderClient {

    /** Stable provider label recorded on the transaction log. */
    static final String PROVIDER_NAME = "TEST_MODE";

    private final TestModeTaxCalculator calculator;

    public TestModeTaxProvider(TestModeTaxCalculator calculator) {
        this.calculator = calculator;
    }

    @Override
    @NonNull
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @NonNull
    public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
        return calculator.calculate(request);
    }

    @Override
    @NonNull
    public TaxCalculationResponse refund(@NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
        // In test mode a refund is priced with the same calculator; calculationType=REFUND on
        // the request drives positive amounts at the supplied original sale date (story T4).
        return calculator.calculate(request);
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.COMMITTED, null, "test-mode commit (no-op)");
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.VOIDED, null, "test-mode void (no-op)");
    }
}
