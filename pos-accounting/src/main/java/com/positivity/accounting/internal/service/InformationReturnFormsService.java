package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import org.jspecify.annotations.NonNull;

/**
 * The information-return forms of the tenant's tax country (CAP:550 #2615): pos-tax's configured forms, boxes and
 * payee-id schemes for {@code accounting.tax.country}, relayed through accounting's front door (ADR-0071, AW59) and
 * used to validate a vendor's information-return flag. Reads only; nothing is stored.
 */
public interface InformationReturnFormsService {

    /**
     * The forms pos-tax configures for the tax country.
     *
     * @return the forms; empty when the country configures none
     * @throws com.positivity.accounting.internal.exception.TaxServiceUnavailableException 503 when pos-tax cannot answer
     */
    @NonNull
    InformationReturnFormsResponse forms();
}
