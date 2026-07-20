package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.MappingResolutionTestRequest;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse;
import org.jspecify.annotations.NonNull;

/**
 * Dry-run posting rule / mapping resolution service (story E3, issue #957).
 *
 * Resolves a hypothetical accounting event against the published posting
 * rules and GL mappings, returning exactly what the posting engine would
 * post — matched rule identity, mapping details, resolved lines (including
 * E1 proportional split shares and residual distribution), and per-predicate
 * evaluation outcomes (E2 grammar) — <strong>without persisting
 * anything</strong>.
 *
 * Contract:
 * <ul>
 * <li>The implementation MUST reuse the production evaluation machinery
 * ({@link PostingRuleEvaluator}, {@code PredicateParser},
 * {@link GLMappingResolver}) so dry-run output is identical to what a real
 * event would produce. The recommended approach is to build a transient
 * (never-saved) {@code AccountingEvent} from the request and pass it to the
 * evaluator; no accounting event, journal entry, outbox record, or any other
 * row may be written as a side effect.</li>
 * <li>No matching published rule for the event type / transaction date is a
 * normal outcome: return {@code matched=false} with a populated
 * {@code noMatchReason}/{@code noMatchDetail}. Do NOT throw.</li>
 * <li>A sample payload that cannot be interpreted by the rules (for example
 * a non-numeric amount field) is a caller error: throw
 * {@link IllegalArgumentException} with a descriptive message; the module
 * exception handler maps it to HTTP 400 {@code VALIDATION_ERROR}.</li>
 * </ul>
 *
 * @see PostingRuleEvaluator
 * @see GLMappingResolver
 */
public interface MappingResolutionTestService {

    /**
     * Performs a dry-run resolution of the supplied hypothetical event.
     *
     * @param request the dry-run request (validated: non-blank event type,
     *                non-null transaction date; sample payload optional)
     * @return the dry-run resolution result; {@code matched=false} when no
     *         published rule matched (never null)
     * @throws IllegalArgumentException if the sample payload cannot be
     *                                  interpreted by the matched rules
     *                                  (mapped to HTTP 400)
     */
    @NonNull
    MappingResolutionTestResponse resolveTest(@NonNull MappingResolutionTestRequest request);
}
