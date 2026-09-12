package com.positivity.tax.internal.repository;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the exemption-certificate lookup (story T3, decision D-T2), built as a
 * {@link Specification} so that an absent filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how this
 * lookup was written until this fix. It works on H2 and failed on PostgreSQL whenever the state
 * scope was absent — which is most calls, since {@code destinationAddress.regionCode} is an
 * optional field of a tax calculation request — with {@code function upper(bytea) does not exist}.
 * Every {@code POST /v1/tax/calculate} carrying an exemption claim without a region code became a
 * 500 while the H2-backed tests of the same query stayed green. This is a variant of the defect of
 * issue #1891.
 *
 * <p>The placeholder that trips is {@code stateScope}, and the reason is worth stating exactly,
 * because it is <em>not</em> the temporal case #1891 documents. A {@code String} bound to a value —
 * or even bound to {@code setNull} — carries a concrete varchar type OID and infers fine; that is
 * why the neighbouring {@code reasonCode} clause and this one's own {@code ? is null} test both
 * parse. What has no type here is the parameter itself: {@code :stateScope} never appears beside a
 * column Hibernate could read a type from, only in {@code IS NULL} and inside {@code upper(…)}, so
 * Hibernate falls back to binding it as an opaque binary and PostgreSQL is asked for an
 * {@code upper(bytea)} that does not exist. The rule the specification form obeys is the general
 * one behind both variants: <em>a parameter that appears only in an {@code IS NULL} test has no
 * type to infer</em>.
 *
 * <p>Building the predicate list removes the failure by construction rather than by casting each
 * placeholder: an absent filter contributes no predicate, so there is no untyped placeholder for
 * any dialect to reject, and no way to reintroduce one by adding a filter here later.
 *
 * <h2>Matching rules</h2>
 *
 * A certificate is a candidate when it is {@link ExemptionCertificateStatus#ACTIVE} and the
 * transaction date falls inside its window, which is inclusive at both ends — a certificate is
 * usable on the day it takes effect and on the day it expires — with a null {@code expiresAt}
 * meaning no expiry. A certificate that names no state scope of its own applies in every state, so
 * a scoped request still matches it; a scoped certificate matches only its own scope, compared
 * case-insensitively because the scope reaches us as whatever the caller typed.
 */
final class ActiveCertificateSearch {

    private static final String CUSTOMER_ID = "customerId";
    private static final String STATE_SCOPE = "stateScope";
    private static final String REASON_CODE = "reasonCode";
    private static final String STATUS = "status";
    private static final String EFFECTIVE_FROM = "effectiveFrom";
    private static final String EXPIRES_AT = "expiresAt";

    private ActiveCertificateSearch() {}

    /**
     * The certificates that could back an exemption for one customer on one date.
     *
     * <p>Both filters are optional: a null argument switches its predicate off rather than matching
     * nothing, so a caller that knows neither the state nor the reason sees every active
     * certificate the customer holds.
     *
     * @param customerId the customer whose certificates to consider; always required
     * @param stateScope the destination state the transaction is taxed in, or null to accept a
     *     certificate of any scope
     * @param reasonCode the exemption reason claimed, or null to accept any reason
     * @param date the transaction date the certificate has to be valid on
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static Specification<ExemptionCertificate> matching(
            @NonNull String customerId,
            @Nullable String stateScope,
            @Nullable ExemptionReasonCode reasonCode,
            @NonNull LocalDate date) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(6);
            predicates.add(builder.equal(root.get(CUSTOMER_ID), customerId));
            predicates.add(builder.equal(root.get(STATUS), ExemptionCertificateStatus.ACTIVE));
            predicates.add(builder.lessThanOrEqualTo(root.get(EFFECTIVE_FROM), date));
            predicates.add(builder.or(
                    builder.isNull(root.get(EXPIRES_AT)), builder.greaterThanOrEqualTo(root.get(EXPIRES_AT), date)));
            if (stateScope != null) {
                // Folded to upper case here rather than with a second SQL upper(?): the comparison
                // then has a plain literal on one side, which is one fewer placeholder to type.
                predicates.add(builder.or(
                        builder.isNull(root.get(STATE_SCOPE)),
                        builder.equal(builder.upper(root.get(STATE_SCOPE)), stateScope.toUpperCase(Locale.ROOT))));
            }
            if (reasonCode != null) {
                predicates.add(builder.equal(root.get(REASON_CODE), reasonCode));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
