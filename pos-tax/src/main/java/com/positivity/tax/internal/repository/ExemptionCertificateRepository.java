package com.positivity.tax.internal.repository;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

/**
 * Spring Data repository for {@link ExemptionCertificate} (story T3).
 */
public interface ExemptionCertificateRepository extends JpaRepository<ExemptionCertificate, java.util.UUID> {

    /**
     * List certificates for a customer, newest effective first.
     *
     * @param customerId the customer id
     * @return certificates for the customer
     */
    List<ExemptionCertificate> findByCustomerIdOrderByEffectiveFromDesc(String customerId);

    /**
     * Find the certificates active for a customer on {@code date}, ordered most-recently
     * effective first.
     * <p>
     * "Active" means status {@link ExemptionCertificateStatus#ACTIVE},
     * {@code effectiveFrom <= date} and ({@code expiresAt} is {@code null} or
     * {@code expiresAt >= date}). When {@code stateScope} is supplied, a certificate matches
     * if its own scope is {@code null} (all states) or equals the supplied scope; when
     * {@code reasonCode} is supplied it must match. The caller takes the first result.
     *
     * @param customerId the customer id (required)
     * @param stateScope optional state scope filter
     * @param reasonCode optional reason filter
     * @param date       the transaction date
     * @param limit      row limit (caller passes {@code Limit.of(1)})
     * @return matching active certificates, most-recently effective first
     */
    @Query("""
            select c from ExemptionCertificate c
            where c.customerId = :customerId
              and c.status = com.positivity.tax.internal.enums.ExemptionCertificateStatus.ACTIVE
              and c.effectiveFrom <= :date
              and (c.expiresAt is null or c.expiresAt >= :date)
              and (:stateScope is null or c.stateScope is null or upper(c.stateScope) = upper(:stateScope))
              and (:reasonCode is null or c.reasonCode = :reasonCode)
            order by c.effectiveFrom desc
            """)
    List<ExemptionCertificate> findActiveForCustomer(
            @Param("customerId") String customerId,
            @Param("stateScope") @Nullable String stateScope,
            @Param("reasonCode") @Nullable ExemptionReasonCode reasonCode,
            @Param("date") LocalDate date,
            Limit limit);
}
