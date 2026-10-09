package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtTaxRegistration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The bound tenant's copy of its tax registrations (CAP:550 S32c); the tenant filter and RLS scope every read. */
public interface ExtTaxRegistrationRepository extends JpaRepository<ExtTaxRegistration, UUID> {

    /**
     * The registrations of one country's regime in effect on {@code asOf} (both ends inclusive, AW49). pos-tax keeps
     * at most one in effect on a date, so this holds zero or one row.
     */
    @Query("select r from ExtTaxRegistration r where r.countryCode = :countryCode and r.regime = :regime"
            + " and r.effectiveFrom <= :asOf and (r.effectiveTo is null or r.effectiveTo >= :asOf)")
    @NonNull
    List<ExtTaxRegistration> findInEffectOn(
            @Param("countryCode") @NonNull String countryCode,
            @Param("regime") @NonNull String regime,
            @Param("asOf") @NonNull LocalDate asOf);
}
