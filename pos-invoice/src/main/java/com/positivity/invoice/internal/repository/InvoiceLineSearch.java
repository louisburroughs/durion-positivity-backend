package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the party line search backing {@code GET /v1/invoices/items/search}, built as a
 * {@link Specification} so that an absent description filter emits no SQL at all.
 *
 * <h2>Why not {@code (:q IS NULL OR LOWER(col) LIKE LOWER(CONCAT('%', :q, '%')))}</h2>
 *
 * That is how this search was written until issue #1891, and on PostgreSQL it made every call that
 * omitted the description filter a 500 — the ordinary case, since the filter is optional and the
 * endpoint's main job is "show me this party's lines". It is the same family of defect as the
 * invoice finder next door but a different symptom: an optional {@code String} survives {@code ? IS
 * NULL} on its own, because pgjdbc gives a varchar a concrete type OID for a value and for {@code
 * setNull} alike — but Hibernate binds the null of this parameter untyped, and the surrounding
 * {@code CONCAT} then leaves PostgreSQL to resolve {@code unknown || unknown} by itself. It resolves
 * it to {@code bytea}, and the statement is rejected at parse time with {@code function lower(bytea)
 * does not exist}. Supplying a term hid the defect completely: a bound varchar types the operator
 * and the same statement parses, which is why the two filtered cases stayed green while the
 * unfiltered one had never worked on PostgreSQL at all.
 *
 * <p>Building the predicate list removes both failure modes by construction rather than by casting
 * the placeholder: an absent filter contributes no predicate, so there is no untyped placeholder for
 * any dialect to guess a type for.
 *
 * <h2>The owning invoice is fetched, not just joined</h2>
 *
 * The result mapping reads the invoice's number, status and creation time outside a lazy-loading
 * session, and the association is to-one, so a fetch join cannot multiply rows and pagination stays
 * in SQL. The order — newest invoice first, then line id — is expressed here rather than left to a
 * {@code Sort}, so that it uses this same join instead of provoking a second one.
 */
final class InvoiceLineSearch {

    /**
     * The escape character of the description {@code LIKE}, matching the pattern the service
     * builds: a term containing {@code %} or {@code _} is a literal part number, not a wildcard.
     */
    private static final char LIKE_ESCAPE = '\\';

    private static final String INVOICE = "invoice";
    private static final String PARTY_ID = "partyId";
    private static final String DESCRIPTION = "description";
    private static final String CREATED_AT = "createdAt";
    private static final String ID = "id";

    private InvoiceLineSearch() {}

    /**
     * The line-search filter: every line of one party's invoices, newest invoice first, optionally
     * narrowed by a description term.
     *
     * @param partyId the customer party id the owning invoice is billed to (stored as a string)
     * @param descriptionTerm a pre-escaped {@code LIKE} term matched as a substring of the line
     *     description, case-insensitively, or null to return every line
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static Specification<InvoiceItem> matching(@NonNull String partyId, @Nullable String descriptionTerm) {
        return (root, query, builder) -> {
            // The fetch and the join are the same node; JPA models them as unrelated interfaces,
            // so the cast is how a predicate or an ordering reaches the fetched association without
            // provoking a second join to the same table.
            Fetch<InvoiceItem, Invoice> fetched = root.fetch(INVOICE, JoinType.INNER);
            Join<InvoiceItem, Invoice> invoice = (Join<InvoiceItem, Invoice>) fetched;

            List<Predicate> predicates = new ArrayList<>(2);
            predicates.add(builder.equal(invoice.get(PARTY_ID), partyId));
            if (descriptionTerm != null) {
                predicates.add(builder.like(
                        builder.lower(root.get(DESCRIPTION)),
                        "%" + descriptionTerm.toLowerCase(Locale.ROOT) + "%",
                        LIKE_ESCAPE));
            }
            if (query != null) {
                query.orderBy(builder.desc(invoice.get(CREATED_AT)), builder.asc(root.get(ID)));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
