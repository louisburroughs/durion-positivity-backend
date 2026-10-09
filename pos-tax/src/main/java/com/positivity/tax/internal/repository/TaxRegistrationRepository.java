package com.positivity.tax.internal.repository;

import com.positivity.tax.internal.entity.TaxRegistration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bound tenant's tax registrations (CAP:550 S32c); Hibernate's tenant filter and RLS scope every read. */
public interface TaxRegistrationRepository extends JpaRepository<TaxRegistration, UUID> {

    /**
     * Every registration of one country's regime, whatever its dates: the candidates an overlap check compares a
     * write with. A tenant holds a handful per regime, so the comparison runs in the service.
     */
    @NonNull
    List<TaxRegistration> findByCountryCodeAndRegime(@NonNull String countryCode, @NonNull String regime);
}
