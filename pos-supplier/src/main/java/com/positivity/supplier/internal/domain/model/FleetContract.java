package com.positivity.supplier.internal.domain.model;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A fleet contract as the vendor states it (CAP-323 #1229).
 *
 * <p>Dates are the vendor's own and are not used to decide whether the contract is live. That
 * decision is the vendor's, made when authorization is requested: a contract that looks expired
 * here may have been renewed, and refusing locally would deny work the fleet would have paid for.
 *
 * @param vendorContractId  the vendor's identifier, quoted on an authorization request
 * @param name              the contract's name, as stated
 * @param contractorId      the fleet operator's identifier at the vendor
 * @param contractorName    the fleet operator's name, as stated
 * @param startDate         as stated; may be absent or unparseable, in which case null
 * @param endDate           as stated; absence does not mean open-ended
 */
public record FleetContract(
        @Nullable String vendorContractId,
        @Nullable String name,
        @Nullable String contractorId,
        @Nullable String contractorName,
        @Nullable LocalDate startDate,
        @Nullable LocalDate endDate) {}
