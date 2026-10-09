package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.PermissionHoldersResponse;
import java.util.Collection;
import java.util.List;

/**
 * Which of the caller's tenant's roles hold given permission codes (#2669): the "Who can do what"
 * column of the Approval limits page.
 *
 * <p>Roles only, never users or counts (decision D1). The configured grants of the bound tenant,
 * read at call time under row-level security (ADR-0062); nothing is cached.
 */
public interface PermissionHolderService {

    /** The most distinct codes one request may ask about. */
    int MAX_CODES = 20;

    /** The longest code accepted, in characters. */
    int MAX_CODE_LENGTH = 255;

    /**
     * Answers which roles hold each requested code.
     *
     * <p>Checks run in this order and stop at the first refusal: shape (400), scope (403), then
     * registration (422). The scope check runs before anything is read, so a scoped caller can
     * neither see holders outside its scope nor probe which codes exist.
     *
     * @param requested         the raw {@code permission} values; trimmed, matched against the
     *                          catalog ignoring case, and de-duplicated in first-seen order
     * @param callerAuthorities the caller's authority codes
     * @return one entry per distinct code, in request order, each in the catalog's spelling
     * @throws com.positivity.securityservice.internal.exception.PermissionHolderQueryInvalidException
     *         no code, more than {@link #MAX_CODES} distinct codes, or a code that is not
     *         {@code domain:resource:action}
     * @throws com.positivity.securityservice.internal.exception.PermissionHolderScopeDeniedException
     *         the caller lacks {@code security:role:view} and a code is outside its read scope
     * @throws com.positivity.securityservice.internal.exception.PermissionNotRegisteredException
     *         a code is not in the permission catalog
     */
    PermissionHoldersResponse listPermissionHolders(List<String> requested, Collection<String> callerAuthorities);
}
