package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.PermissionHolderRole;
import com.positivity.securityservice.internal.dto.PermissionHolderRow;
import com.positivity.securityservice.internal.dto.PermissionHolders;
import com.positivity.securityservice.internal.dto.PermissionHoldersResponse;
import com.positivity.securityservice.internal.exception.PermissionHolderQueryInvalidException;
import com.positivity.securityservice.internal.exception.PermissionHolderScopeDeniedException;
import com.positivity.securityservice.internal.exception.PermissionNotRegisteredException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.security.PermissionHolderReadScopes;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the permission-holders read from the bound tenant's {@code role_permissions} (#2669).
 *
 * <p>Tenant isolation is row-level security's job: neither query takes a tenant, and only the
 * bound tenant's roles and grants are visible (ADR-0062). The permission catalog is platform-global.
 * Nothing logs a role name: a custom role's name is tenant-authored text (ADR-0072).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionHolderServiceImpl implements PermissionHolderService {

    /**
     * {@code domain:resource:action}, each part a letter then letters, digits, {@code _} or
     * {@code -}. Either case: the catalog holds camelCase codes such as
     * {@code people:timeEntry:approve}.
     */
    private static final Pattern CODE_SHAPE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_-]*:[A-Za-z][A-Za-z0-9_-]*:[A-Za-z][A-Za-z0-9_-]*");

    private static final Comparator<PermissionHolderRole> BY_NAME = Comparator.comparing(
                    PermissionHolderRole::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(PermissionHolderRole::name);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Override
    public PermissionHoldersResponse listPermissionHolders(
            List<String> requested, Collection<String> callerAuthorities) {
        List<String> codes = normalise(requested);
        requireWellFormed(codes);
        requireInScope(codes, callerAuthorities);
        List<String> canonical = resolveRegistered(codes);

        Map<String, List<PermissionHolderRole>> byPermission = new HashMap<>();
        for (PermissionHolderRow row : roleRepository.findHolderRowsByPermissionNames(canonical)) {
            byPermission
                    .computeIfAbsent(row.permission(), code -> new ArrayList<>())
                    .add(new PermissionHolderRole(row.roleName(), row.templateKey(), row.locationScope()));
        }
        List<PermissionHolders> entries = canonical.stream()
                .map(code -> new PermissionHolders(
                        code,
                        byPermission.getOrDefault(code, List.of()).stream()
                                .sorted(BY_NAME)
                                .toList()))
                .toList();
        return new PermissionHoldersResponse(entries);
    }

    /**
     * Trim and de-duplicate ignoring case, keeping the first-seen spelling and order. Codes are
     * matched against the catalog case-insensitively, so two spellings of one code are one code. A
     * null value becomes blank and fails the shape check.
     */
    private static List<String> normalise(List<String> requested) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (requested != null) {
            for (String value : requested) {
                String code = value == null ? "" : value.trim();
                byKey.putIfAbsent(key(code), code);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static String key(String code) {
        return code.toLowerCase(Locale.ROOT);
    }

    private static void requireWellFormed(List<String> codes) {
        List<String> problems = new ArrayList<>();
        if (codes.isEmpty()) {
            problems.add("at least one permission code is required");
        }
        if (codes.size() > MAX_CODES) {
            problems.add("at most " + MAX_CODES + " distinct permission codes may be requested; " + codes.size()
                    + " were sent");
        }
        for (String code : codes) {
            if (code.length() > MAX_CODE_LENGTH || !CODE_SHAPE.matcher(code).matches()) {
                problems.add("'" + code + "' is not a domain:resource:action permission code");
            }
        }
        if (!problems.isEmpty()) {
            throw new PermissionHolderQueryInvalidException(problems);
        }
    }

    /**
     * Decision D2: {@code security:role:view} may ask about any code; otherwise only the codes a
     * held scope entry governs, compared ignoring case. Runs before any read — the catalog lookup
     * included — so a refused caller learns nothing, not even whether a code exists.
     */
    private static void requireInScope(List<String> codes, Collection<String> callerAuthorities) {
        if (callerAuthorities.contains(SecurityPermissions.ROLE_VIEW)) {
            return;
        }
        Set<String> readable = PermissionHolderReadScopes.readableCodes(callerAuthorities).stream()
                .map(PermissionHolderServiceImpl::key)
                .collect(Collectors.toSet());
        List<String> outOfScope =
                codes.stream().filter(code -> !readable.contains(key(code))).toList();
        if (!outOfScope.isEmpty()) {
            throw new PermissionHolderScopeDeniedException(outOfScope);
        }
    }

    /**
     * Resolves each requested code to the catalog's spelling, matching ignoring case, and refuses
     * the request when any code is not registered. Where the catalog itself held two spellings of
     * one code, the one written exactly as requested wins, else the first in sort order.
     *
     * @return the canonical codes, in request order
     */
    private List<String> resolveRegistered(List<String> codes) {
        Map<String, SortedSet<String>> catalogByKey = new HashMap<>();
        for (String name : permissionRepository.findNamesIgnoreCase(
                codes.stream().map(PermissionHolderServiceImpl::key).toList())) {
            catalogByKey.computeIfAbsent(key(name), k -> new TreeSet<>()).add(name);
        }
        List<String> unregistered = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (String code : codes) {
            SortedSet<String> spellings = catalogByKey.get(key(code));
            if (spellings == null) {
                unregistered.add(code);
            } else {
                canonical.add(spellings.contains(code) ? code : spellings.first());
            }
        }
        if (!unregistered.isEmpty()) {
            throw new PermissionNotRegisteredException(unregistered);
        }
        return List.copyOf(canonical);
    }
}
