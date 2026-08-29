package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public interface DomainLoaderStrategy<T> {

    DomainType getDomainType();

    T mapRow(@NonNull Map<String, String> row);

    List<String> validate(@NonNull T item);

    /**
     * Replaces the business keys in a mapped row with the ids its ingest endpoint expects, using
     * live lookups against the owning services.
     *
     * <p>Runs before {@link #validate}, so that a file keyed entirely by names has ids to validate
     * by the time validation looks. Report a key that could not be resolved by leaving its id
     * unset — the ordinary "id is required" rule then rejects the row, and it lands in the review
     * queue where an operator can see it. Never substitute a fallback: a row that silently lost its
     * id is accepted, stored, and wrong, which is far worse than one that visibly failed.
     *
     * <p>Because it runs first, it may be handed a row that is malformed in other ways, so it must
     * tolerate missing fields rather than assume validation has already passed.
     *
     * <p>Default is a no-op, for the domains whose files already carry ids.
     */
    @NonNull
    default T resolve(@NonNull T item, @NonNull ResolutionContext context) {
        return item;
    }
}
