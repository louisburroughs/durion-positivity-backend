package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the invoice finder (#1599, E11) backing {@code GET /v1/invoices/search}, built as a
 * {@link Specification} so that an absent filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how this
 * finder was written until issue #1891. It works on H2 and fails on PostgreSQL for <em>every</em>
 * call: pgjdbc sends a temporal value — and a temporal {@code setNull} — with the type OID left
 * unspecified so the server may coerce between {@code timestamp} and {@code timestamptz}, which
 * leaves {@code ? IS NULL} over one with nothing to infer a type from. PostgreSQL rejects the whole
 * statement at parse time, before any value is bound, with {@code could not determine data type of
 * parameter $n} — which is why supplying the filter did not help either. Every request to the
 * invoice finder became a 500 while the H2-backed tests of the same query stayed green.
 *
 * <p>The two placeholders that actually tripped were the {@code issuedFrom}/{@code issuedTo} bounds
 * on {@code finalizedAt}. The {@code status} (enum) and {@code customerId} ({@code String})
 * placeholders in the same statement inferred fine — both are bound as varchar with a concrete type
 * OID — but rejection is of the statement, not of a clause, so they went down with it.
 *
 * <p>Building the predicate list removes the failure by construction rather than by casting each
 * placeholder: a null filter contributes no predicate, so there is no untyped placeholder for any
 * dialect to reject, and no way to reintroduce one by adding a filter here later. It also keeps the
 * page query and its count in step for free — Spring Data derives the count from this same
 * specification, so the two cannot drift apart.
 *
 * <h2>The window is on {@code finalizedAt}</h2>
 *
 * When the invoice was issued to the customer, frozen at finalization alongside {@code dueDate} —
 * not {@code createdAt}, which reports when the draft was first opened. A DRAFT invoice has no
 * {@code finalizedAt}, so it falls outside every window, which is the documented behaviour of
 * {@code InvoiceSearchFilters}. Both bounds are inclusive, matching the calendar-day semantics the
 * service builds them with.
 */
final class InvoiceSearch {

    /**
     * The escape character of the free-text {@code LIKE}, matching the pattern the service builds:
     * an operator pasting an invoice number containing {@code _} is quoting a literal reference,
     * not writing a wildcard.
     */
    private static final char LIKE_ESCAPE = '\\';

    private static final String INVOICE_NUMBER = "invoiceNumber";
    private static final String PARTY_ID = "partyId";
    private static final String WORKORDER_ID = "workorderId";
    private static final String STATUS = "status";
    private static final String FINALIZED_AT = "finalizedAt";

    private InvoiceSearch() {}

    /**
     * The finder's filter. Every clause is optional: a null (or, for the free-text leg, blank)
     * argument switches its predicate off rather than matching nothing, so a call carrying only
     * structured filters pages every invoice they admit.
     *
     * @param searchTerm a pre-escaped {@code LIKE} term matched as a substring of the invoice
     *     number, case-insensitively; blank disables the whole free-text leg
     * @param partyIds party ids resolved from the customer-name leg, matched as alternatives to the
     *     invoice number; empty contributes nothing
     * @param workorderIds workorder ids resolved from the workorder-number leg, likewise
     * @param status only invoices in this status, or null for every status
     * @param issuedFrom inclusive lower bound on {@code finalizedAt}, or null
     * @param issuedTo inclusive upper bound on {@code finalizedAt}, or null
     * @param customerId only invoices billed to this party, or null for every party
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static Specification<Invoice> matching(
            @Nullable String searchTerm,
            @NonNull Collection<String> partyIds,
            @NonNull Collection<UUID> workorderIds,
            @Nullable InvoiceStatus status,
            @Nullable Instant issuedFrom,
            @Nullable Instant issuedTo,
            @Nullable String customerId) {
        boolean freeText = searchTerm != null && !searchTerm.isEmpty();
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            if (freeText) {
                List<Predicate> alternatives = new ArrayList<>(3);
                alternatives.add(builder.like(
                        builder.lower(root.get(INVOICE_NUMBER)),
                        "%" + searchTerm.toLowerCase(Locale.ROOT) + "%",
                        LIKE_ESCAPE));
                if (!partyIds.isEmpty()) {
                    alternatives.add(root.get(PARTY_ID).in(partyIds));
                }
                if (!workorderIds.isEmpty()) {
                    alternatives.add(root.get(WORKORDER_ID).in(workorderIds));
                }
                predicates.add(builder.or(alternatives.toArray(new Predicate[0])));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get(STATUS), status));
            }
            if (issuedFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get(FINALIZED_AT), issuedFrom));
            }
            if (issuedTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get(FINALIZED_AT), issuedTo));
            }
            if (customerId != null) {
                predicates.add(builder.equal(root.get(PARTY_ID), customerId));
            }
            // An empty conjunction is every invoice, which is what a caller supplying neither a
            // query term nor a structured filter is asking for; the service decides separately
            // whether such a call is worth issuing at all.
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
