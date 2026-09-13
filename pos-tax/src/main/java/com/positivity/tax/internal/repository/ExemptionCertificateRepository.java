package com.positivity.tax.internal.repository;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link ExemptionCertificate} (story T3).
 */
public interface ExemptionCertificateRepository
        extends JpaRepository<ExemptionCertificate, UUID>, JpaSpecificationExecutor<ExemptionCertificate> {

    /**
     * The lookup order: most recently effective first, so the first result is the certificate that
     * supersedes the others. Imposed by the search rather than taken from the caller, because it is
     * what makes "the caller takes the first result" a well-defined rule.
     */
    Sort BY_EFFECTIVE_FROM_DESC = Sort.by(Sort.Order.desc("effectiveFrom"));

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
     * <p>The filter is an {@link ActiveCertificateSearch} specification rather than a JPQL string
     * of {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL whenever the state scope was absent, while passing on H2 (issue #1891).
     *
     * @param customerId the customer id (required)
     * @param stateScope optional state scope filter
     * @param reasonCode optional reason filter
     * @param date       the transaction date
     * @param limit      row limit (caller passes {@code Limit.of(1)})
     * @return matching active certificates, most-recently effective first
     */
    @NonNull
    default List<ExemptionCertificate> findActiveForCustomer(
            @NonNull String customerId,
            @Nullable String stateScope,
            @Nullable ExemptionReasonCode reasonCode,
            @NonNull LocalDate date,
            @NonNull Limit limit) {
        return findBy(ActiveCertificateSearch.matching(customerId, stateScope, reasonCode, date), query -> {
            var sorted = query.sortBy(BY_EFFECTIVE_FROM_DESC);
            // Limit.unlimited() reports a max of -1, which the fluent query rejects, so an
            // unlimited request has to skip the limit rather than pass the sentinel on.
            return (limit.isLimited() ? sorted.limit(limit.max()) : sorted).all();
        });
    }
}
