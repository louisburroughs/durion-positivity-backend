package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/** See {@link InformationReturnFormsService}. No code here names a country: the tax country is a setting. */
@Service
@RequiredArgsConstructor
public class InformationReturnFormsServiceImpl implements InformationReturnFormsService {

    private final TaxCountry taxCountry;
    private final TaxReferenceClient taxReference;

    @Override
    public @NonNull InformationReturnFormsResponse forms() {
        return taxReference.informationReturnForms(taxCountry.code());
    }
}
