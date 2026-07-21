package com.positivity.mcp.internal.orchestration.retrieval;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Gate 5 (G5.1): permission-aware RAG visibility filter. Wraps a {@link ContentRetriever} and keeps
 * only documents the caller is allowed to see, based on the document's {@code required_permissions}
 * metadata (set at ingest by G5.2) intersected with the caller's permission codes.
 *
 * <p>This replaces the role-only {@link RoleAwareMetadataFilter} (a pass-through stub): visibility is
 * permission-first, not role-based. Rules per document:
 * <ul>
 *   <li>no {@code required_permissions} metadata (or empty) → public → visible;
 *   <li>contains {@code AUTHENTICATED} → visible to any authenticated caller;
 *   <li>otherwise → visible only if the caller holds at least one of the required codes.
 * </ul>
 *
 * <p>Fail-closed for restricted docs: if the doc lists codes the caller lacks, it is dropped.
 */
public class PermissionAwareMetadataFilter implements QueryDocumentRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionAwareMetadataFilter.class);
    static final String REQUIRED_PERMISSIONS = "required_permissions";
    static final String AUTHENTICATED = "AUTHENTICATED";

    private final QueryDocumentRetriever delegate;
    private final Set<String> callerPermissionCodes;

    public PermissionAwareMetadataFilter(
            @NonNull QueryDocumentRetriever delegate, @NonNull Set<String> callerPermissionCodes) {
        this.delegate = delegate;
        this.callerPermissionCodes = Set.copyOf(callerPermissionCodes);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        List<Document> candidates = delegate.retrieve(queryText);
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<Document> visible = candidates.stream().filter(this::isVisible).collect(Collectors.toList());
        int dropped = candidates.size() - visible.size();
        if (dropped > 0) {
            LOGGER.debug(
                    "Permission-aware RAG filter callerCodes={} candidates={} kept={} dropped={}",
                    callerPermissionCodes.size(),
                    candidates.size(),
                    visible.size(),
                    dropped);
        }
        return visible;
    }

    private boolean isVisible(@NonNull Document document) {
        Set<String> required = requiredPermissions(document);
        if (required.isEmpty()) {
            return true; // public / unrestricted
        }
        if (required.contains(AUTHENTICATED)) {
            // Visible to any authenticated caller. CurrentUserContext always carries the synthetic
            // AUTHENTICATED code, so gate on its presence — safe even if this retriever is ever used
            // outside an authenticated endpoint.
            return callerPermissionCodes.contains(AUTHENTICATED);
        }
        return required.stream().anyMatch(callerPermissionCodes::contains);
    }

    private static Set<String> requiredPermissions(@NonNull Document document) {
        Object rawValue = document.getMetadata().get(REQUIRED_PERMISSIONS);
        if (rawValue == null) {
            return Set.of();
        }
        String raw = String.valueOf(rawValue);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String toString() {
        return "PermissionAwareMetadataFilter{callerCodeCount=" + callerPermissionCodes.size() + '}';
    }
}
