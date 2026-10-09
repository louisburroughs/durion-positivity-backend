package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.TaxRegimesResponse;
import com.positivity.accounting.internal.dto.TaxTypesReference;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * See {@link TaxRegimesService}. No code here names a country, regime or tax type: the country is the caller's or the
 * tax country setting, and every regime and tax type is pos-tax configuration.
 *
 * <p>pos-tax's answer is grouped by regime: each regime, in configured order, carries the tax types declared under it.
 * A tax type with no regime cannot be registered for, so it is not served. An answer missing a required list or field,
 * or naming a regime it does not declare, is unreadable and answers 503 like an unreachable pos-tax: it is never read as
 * "no regimes" (ADR-0017).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxRegimesServiceImpl implements TaxRegimesService {

    private final TaxCountry taxCountry;
    private final TaxReferenceClient taxReference;

    @Override
    public @NonNull TaxRegimesResponse regimes(@Nullable String countryCode) {
        String country = countryCode == null ? taxCountry.code() : countryCode;
        TaxTypesReference answer = taxReference.taxTypes(country);
        if (answer.regimes() == null || answer.taxTypes() == null || answer.source() == null) {
            throw unreadable("a required list or the source is missing");
        }

        Map<String, List<TaxRegimesResponse.TaxType>> taxTypesByRegime = new LinkedHashMap<>();
        for (TaxTypesReference.Regime regime : answer.regimes()) {
            if (regime == null || regime.regime() == null || regime.regions() == null) {
                throw unreadable("a regime lacks its code or regions");
            }
            taxTypesByRegime.put(regime.regime(), new ArrayList<>());
        }
        for (TaxTypesReference.TaxType taxType : answer.taxTypes()) {
            if (taxType == null || taxType.taxType() == null || taxType.jurisdictionType() == null) {
                throw unreadable("a tax type lacks its code or jurisdiction level");
            }
            if (taxType.regime() == null) {
                continue;
            }
            List<TaxRegimesResponse.TaxType> underRegime = taxTypesByRegime.get(taxType.regime());
            if (underRegime == null) {
                throw unreadable("a tax type names a regime the country does not declare");
            }
            underRegime.add(new TaxRegimesResponse.TaxType(taxType.taxType(), taxType.jurisdictionType()));
        }

        List<TaxRegimesResponse.Regime> regimes = answer.regimes().stream()
                .map(regime -> new TaxRegimesResponse.Regime(
                        regime.regime(), regime.regions(), taxTypesByRegime.get(regime.regime())))
                .toList();
        return new TaxRegimesResponse(country, answer.source(), regimes);
    }

    /** An answer that cannot be read: logged without any value, answered 503 with Retry-After. */
    private static TaxServiceUnavailableException unreadable(String why) {
        log.error("pos-tax's tax-types answer was unreadable: {}; answering 503", why);
        return new TaxServiceUnavailableException("The tax service is unavailable");
    }
}
