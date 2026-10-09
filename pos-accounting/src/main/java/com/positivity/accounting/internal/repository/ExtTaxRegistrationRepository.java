package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The bound tenant's copy of its tax registrations (CAP:550 S32c); the tenant filter and RLS scope every read. */
public interface ExtTaxRegistrationRepository extends JpaRepository<ExtTaxRegistration, UUID> {

    /** Every registration, in country, regime and start order. */
    @NonNull
    List<ExtTaxRegistration> findAllByOrderByCountryCodeAscRegimeAscEffectiveFromAsc();

    /** The registrations in effect on {@code asOf} (both ends inclusive), in country and regime order (AW49). */
    @Query("select r from ExtTaxRegistration r where r.effectiveFrom <= :asOf"
            + " and (r.effectiveTo is null or r.effectiveTo >= :asOf)"
            + " order by r.countryCode, r.regime, r.effectiveFrom")
    @NonNull
    List<ExtTaxRegistration> findInEffectOn(@Param("asOf") @NonNull LocalDate asOf);
}
