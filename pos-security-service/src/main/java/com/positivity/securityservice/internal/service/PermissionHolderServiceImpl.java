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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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

    /** {@code domain:resource:action}, each part a lower-case letter then letters, digits, {@code _} or {@code -}. */
    private static final Pattern CODE_SHAPE = Pattern.compile("[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*");

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
        requireRegistered(codes);

        Map<String, List<PermissionHolderRole>> byPermission = new HashMap<>();
        for (PermissionHolderRow row : roleRepository.findHolderRowsByPermissionNames(codes)) {
            byPermission
                    .computeIfAbsent(row.permission(), code -> new ArrayList<>())
                    .add(new PermissionHolderRole(row.roleName(), row.templateKey(), row.locationScope()));
        }
        List<PermissionHolders> entries = codes.stream()
                .map(code -> new PermissionHolders(
                        code,
                        byPermission.getOrDefault(code, List.of()).stream()
                                .sorted(BY_NAME)
                                .toList()))
                .toList();
        return new PermissionHoldersResponse(entries);
    }

    /** Trim, lower-case, de-duplicate; first-seen order. A null value becomes blank and fails the shape check. */
    private static List<String> normalise(List<String> requested) {
        Set<String> codes = new LinkedHashSet<>();
        if (requested != null) {
            for (String value : requested) {
                codes.add(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(codes);
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
     * held scope entry governs. Runs before any read, so a refused caller learns nothing.
     */
    private static void requireInScope(List<String> codes, Collection<String> callerAuthorities) {
        if (callerAuthorities.contains(SecurityPermissions.ROLE_VIEW)) {
            return;
        }
        Set<String> readable = PermissionHolderReadScopes.readableCodes(callerAuthorities);
        List<String> outOfScope =
                codes.stream().filter(code -> !readable.contains(code)).toList();
        if (!outOfScope.isEmpty()) {
            throw new PermissionHolderScopeDeniedException(outOfScope);
        }
    }

    private void requireRegistered(List<String> codes) {
        Set<String> registered = permissionRepository.findRegisteredNames(codes);
        List<String> unregistered =
                codes.stream().filter(code -> !registered.contains(code)).toList();
        if (!unregistered.isEmpty()) {
            throw new PermissionNotRegisteredException(unregistered);
        }
    }
}
