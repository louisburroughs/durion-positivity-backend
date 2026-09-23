package com.positivity.people.internal.service;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.EmployeeJobRoleDto;
import com.positivity.people.internal.dto.EmployeeLocationDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeRoleAssignmentDto;
import com.positivity.people.internal.dto.EmployeeStatusCountsResponse;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.EnableEmployeeRequestDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.JobRole;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeSearchInclude;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.ResourceStateConflictException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.JobRoleRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employment lifecycle (ADR-0044 §6 Phase 3.2, #875). Identity attributes (names, contacts) are
 * owned by pos-people-contact: writes travel as {@code people-contact.person.upsert-requested}
 * commands (the sender generates the personId for creates), reads come from the
 * {@code ext_people_contact_person} replica. Create/update responses echo the request's identity
 * fields so callers see their write immediately while the fact event catches the replica up.
 * Every employment mutation also publishes a {@code people.employee.updated} fact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeServiceImpl implements EmployeeService {

    private final Clock clock;

    private static final String SYSTEM_ACTOR = "system";

    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    private final EmployeeRepository employeeRepository;

    private final EmployeeOffboardingRetryRepository offboardingRetryRepository;

    private final PeopleEventPublisher peopleEventPublisher;

    private final JobRoleRepository jobRoleRepository;

    // ── Register enrichment (durion#2155) -- see enrichWindow's javadoc ──

    private final PersonUsernameService personUsernameService;

    private final RoleAssignmentReplicaService roleAssignmentReplicaService;

    private final EmployeeLocationAssignmentRepository employeeLocationAssignmentRepository;

    private final LocationReferenceService locationReferenceService;

    // ── Rendering-hint action flags (durion#2159) -- see EmployeeActionPolicy's javadoc ──

    private final EmployeeActionPolicy employeeActionPolicy;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<EmployeeIdentityDto> resolveByEmployeeNumber(@NonNull String employeeNumber) {
        return employeeRepository
                .findByEmployeeNumberIgnoreCase(employeeNumber)
                .map(employee -> EmployeeIdentityDto.builder()
                        .employeeId(employee.getId())
                        .personId(employee.getPersonId())
                        .employeeNumber(employee.getEmployeeNumber())
                        .status(
                                employee.getStatus() != null
                                        ? employee.getStatus().name()
                                        : null)
                        .active(employee.getStatus() == EmployeeStatus.ACTIVE)
                        .build());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto createEmployee(@NonNull CreateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());
        requireJobRoleExists(request.getJobRoleId());
        List<String> warnings = evaluateDuplicatePolicy(
                null,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getFirstName(),
                request.getLastName());

        // Identity is owned by pos-people-contact: generate the person id here so the employee
        // row can reference it immediately, and send the attributes as an upsert command.
        UUID personId = UUIDv7Generator.generate();
        requestIdentityUpsert(
                personId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo());

        Employee employee = Employee.builder().personId(personId).build();
        applyEmployment(
                employee,
                request.getEmployeeNumber(),
                request.getStatus(),
                request.getHireDate(),
                request.getTerminationDate(),
                request.getJobRoleId());
        Employee savedEmployee = employeeRepository.save(employee);
        peopleEventPublisher.publishEmployeeUpdated(savedEmployee);

        return profileFromRequestIdentity(
                personId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo(),
                savedEmployee,
                warnings);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EmployeeProfileDto getEmployee(@NonNull UUID employeeId) {
        // Pre-split contract: the path parameter is the person id (employee id == person id at
        // the API surface). The replica row may lag a just-issued upsert command, so an existing
        // employee row alone is enough to serve the profile.
        ExtPersonReplica person =
                extPersonReplicaRepository.findById(employeeId).orElse(null);
        Employee employee = employeeRepository.findByPersonId(employeeId).orElse(null);
        if (person == null && employee == null) {
            throw new PersonNotFoundException(employeeId);
        }
        return profileFromReplica(employeeId, person, employee, List.of());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto updateEmployee(
            @NonNull UUID employeeId, @NonNull UpdateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());
        requireJobRoleExists(request.getJobRoleId());

        Employee employee = employeeRepository.findByPersonId(employeeId).orElse(null);
        if (employee == null && !extPersonReplicaRepository.existsById(employeeId)) {
            throw new PersonNotFoundException(employeeId);
        }

        List<String> warnings = evaluateDuplicatePolicy(
                employeeId,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getFirstName(),
                request.getLastName());

        requestIdentityUpsert(
                employeeId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo());

        if (employee == null) {
            employee = Employee.builder().personId(employeeId).build();
        }
        EmployeeStatus previousStatus = employee.getStatus();
        applyEmployment(
                employee,
                request.getEmployeeNumber(),
                request.getStatus(),
                request.getHireDate(),
                request.getTerminationDate(),
                request.getJobRoleId());
        if (previousStatus != request.getStatus()) {
            employee.setStatusEffectiveAt(Instant.now(clock));
        }
        Employee savedEmployee = employeeRepository.save(employee);
        peopleEventPublisher.publishEmployeeUpdated(savedEmployee);

        return profileFromRequestIdentity(
                employeeId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo(),
                savedEmployee,
                warnings);
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto disableEmployee(
            @NonNull UUID employeeId, @NonNull DisableEmployeeRequestDto request) {
        Employee employee = employeeRepository
                .findByPersonId(employeeId)
                .orElseThrow(() -> new PersonNotFoundException(employeeId));

        // Both checks are stateful collisions (ADR-0017 §2: 409), not request-shape validation:
        // the request itself is well-formed, but the employee's current status blocks this
        // transition. Bare IllegalStateException is not used here (issue #1694 follow-up): it is
        // also what SecurityContextHelper throws for a missing security context, which is a
        // server-side defect, not a resource-state collision.
        EmployeeStatus currentStatus = employee.getStatus();
        if (currentStatus == EmployeeStatus.DISABLED || currentStatus == EmployeeStatus.TERMINATED) {
            throw new ResourceStateConflictException("Employee is already DISABLED or TERMINATED");
        }
        if (currentStatus != EmployeeStatus.ACTIVE) {
            throw new ResourceStateConflictException("Only ACTIVE employees can be disabled");
        }

        employee.setStatus(EmployeeStatus.DISABLED);
        employee.setStatusEffectiveAt(Instant.now(clock));
        Employee savedEmployee = employeeRepository.save(employee);
        peopleEventPublisher.publishEmployeeUpdated(savedEmployee);

        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        try {
            applyAssignmentPolicy(employeeId, request, actorId);
        } catch (Exception exception) {
            log.warn(
                    "Offboarding downstream action failed for employee {}. Queuing retry. Reason: {}",
                    employeeId,
                    exception.getMessage());
            queueOffboardingRetry(employeeId, request, actorId, exception.getMessage());
        }

        ExtPersonReplica person =
                extPersonReplicaRepository.findById(employeeId).orElse(null);
        return profileFromReplica(employeeId, person, savedEmployee, List.of());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto enableEmployee(
            @NonNull UUID employeeId, @NonNull EnableEmployeeRequestDto request) {
        Employee employee = employeeRepository
                .findByPersonId(employeeId)
                .orElseThrow(() -> new PersonNotFoundException(employeeId));

        // Stateful collisions, not request-shape validation (ADR-0017 §2: 409) — same reasoning
        // as disableEmployee's guards above: the request is well-formed, but the employee's
        // current status blocks this transition. Bare IllegalStateException is not used for the
        // same reason given there (see ResourceStateConflictException's javadoc, #1694).
        EmployeeStatus currentStatus = employee.getStatus();
        if (currentStatus == EmployeeStatus.TERMINATED) {
            // DECISION-PEOPLE-001: TERMINATED is the irreversible terminal state. DISABLED is the
            // only state this endpoint reverses.
            throw new ResourceStateConflictException("Employee is TERMINATED; termination cannot be reversed");
        }
        if (currentStatus == EmployeeStatus.ON_LEAVE || currentStatus == EmployeeStatus.SUSPENDED) {
            // Both carry an effective date and a reason a bare activate/deactivate switch cannot
            // collect; updateEmployee is the full-profile path that can record them.
            throw new ResourceStateConflictException("Employee is " + currentStatus
                    + "; use updateEmployee to change status, which can record the required"
                    + " effective date and reason");
        }
        if (currentStatus != EmployeeStatus.DISABLED) {
            // Covers ACTIVE (already active — nothing to reactivate) and any status this method
            // does not yet special-case; only a DISABLED employee proceeds past this guard.
            throw new ResourceStateConflictException("Only DISABLED employees can be enabled");
        }

        // Optimistic concurrency (DECISION-PEOPLE-017, as amended), made atomic (#2158 finding
        // C): the state guards above ran against the row as it stood when THIS request read it,
        // which is what lets a TERMINATED/ON_LEAVE/SUSPENDED employee get its specific message
        // rather than a generic conflict — but they cannot by themselves prevent two concurrent
        // requests that both read this same DISABLED row with the same updatedAt from both
        // passing every guard and both writing. The read-compare-then-save shape this replaced
        // had exactly that gap: two callers each load the row, each pass an in-memory
        // Objects.equals(employee.getUpdatedAt(), request.getUpdatedAt()) check, and each call
        // save() — the second silently overwrites the first's write (and its published fact)
        // instead of 409ing. Folding the status check and the token check into one conditional
        // UPDATE (EmployeeRepository#reactivateIfDisabledAndTokenMatches) closes that gap: the
        // database evaluates the WHERE clause row-locked as part of a single statement, so of two
        // callers racing on the same row, only one still finds a matching row to update.
        Instant now = Instant.now(clock);
        int reactivated =
                employeeRepository.reactivateIfDisabledAndTokenMatches(employeeId, request.getUpdatedAt(), now);
        if (reactivated == 0) {
            // The guards above already established DISABLED at the moment this request read the
            // row; zero rows updated here means the row moved between that read and this write —
            // either the token no longer matches, or (the race this fix targets) another request
            // already reactivated it first. Both are "the record changed since it was last read",
            // so both raise the same 409 message the pre-existing in-memory check used.
            throw new ResourceStateConflictException(
                    "Employee has changed since it was last read (updatedAt no longer matches); reload and retry");
        }

        // The conditional UPDATE above is a JPQL bulk statement: it already wrote status,
        // statusEffectiveAt and updatedAt in the database in that one statement (see the
        // repository method's javadoc for why it sets updatedAt itself rather than leaving it to
        // @LastModifiedDate), and clears the persistence context, detaching `employee`. Mirror
        // the same three fields onto it here to build the response and the published fact,
        // rather than issuing a second, redundant save() that would just repeat the write.
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setStatusEffectiveAt(now);
        employee.setUpdatedAt(now);
        peopleEventPublisher.publishEmployeeUpdated(employee);

        // No assignment-policy counterpart here by design: disableEmployee's offboarding ends or
        // grace-periods staffing assignments, but reactivation must not silently resurrect
        // assignments that were deliberately ended — that would restore staffing eligibility the
        // shop never asked for. Any assignment the employee needs after reactivation is created
        // fresh through the staffing-assignment endpoints.
        ExtPersonReplica person =
                extPersonReplicaRepository.findById(employeeId).orElse(null);
        return profileFromReplica(employeeId, person, employee, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<EmployeeSummaryDto> searchEmployees(
            @Nullable String q,
            @Nullable List<EmployeeStatus> status,
            @Nullable String sort,
            int page,
            int size,
            @Nullable List<EmployeeSearchInclude> include) {
        // WARNING to whoever wires up #2155 (widening these rows with data pulled from other
        // service replicas): that enrichment MUST be applied to `window` below — the page this
        // call actually returns — and never to `directory`/`all`/`filtered` here. Those hold
        // every employee in the tenant (214+ and growing), not just the requested page; an extra
        // replica lookup per row done against the full list, instead of the ~20-row window, turns
        // every search call into O(tenant size) outbound calls and makes this register slower
        // than the per-row endpoint it was built to replace.
        Directory directory = loadDirectory();
        List<EmployeeSummaryDto> all = qFilteredSummaries(directory, q);

        List<EmployeeSummaryDto> filtered = all.stream()
                .filter(summary -> matchesStatus(summary, status))
                .sorted(resolveComparator(sort))
                .toList();

        int total = filtered.size();
        int fromIndex = (int) Math.min((long) page * size, total);
        int toIndex = (int) Math.min((long) fromIndex + size, total);
        List<EmployeeSummaryDto> window = filtered.subList(fromIndex, toIndex);

        // durion#2155: enrichment runs HERE, against `window` (the page actually returned, at
        // most `size` rows) -- never against `all`/`filtered`/`directory` above, which hold every
        // employee the search matched. See enrichWindow's javadoc for why that ordering is the
        // whole point of this feature.
        enrichWindow(window, resolveIncludes(include), directory.employeesByPersonId(), directory.replicasByPersonId());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PagedResponse<>(window, page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EmployeeStatusCountsResponse employeeStatusCounts(@Nullable String q) {
        // Deliberately its own load-and-filter pass (loadDirectory + qFilteredSummaries), the same
        // two steps searchEmployees takes before it applies status/sort/paging -- see that
        // method's javadoc and EmployeeStatusCountsResponse's class javadoc for why the histogram
        // is no longer folded into the search response itself (#2158, corrected).
        List<EmployeeSummaryDto> all = qFilteredSummaries(loadDirectory(), q);

        // Every q-filtered employee lands in exactly one bucket -- its status name, or
        // UNKNOWN_STATUS for a null status column (Finding B: a legacy row predating status
        // becoming a required field must still be counted, not silently dropped) -- which is what
        // keeps counts.values() summing to all.size() unconditionally rather than only when every
        // matching employee happens to carry a status.
        Map<String, Long> counts = all.stream()
                .map(summary ->
                        summary.getStatus() != null ? summary.getStatus() : EmployeeStatusCountsResponse.UNKNOWN_STATUS)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return new EmployeeStatusCountsResponse(counts);
    }

    /**
     * Every employee in the tenant (in {@code employeeRepository.findAll()} order — {@code
     * employeesByPersonId} is a lookup index over the same rows, not a substitute iteration
     * order) plus its identity-replica row, indexed by person id (#2158 refactor): the shared
     * load step behind {@link #searchEmployees} (page/sort/status on top) and {@link
     * #employeeStatusCounts} (a histogram over the same q-filtered set, nothing else on top).
     * Employee counts (shop staff) are far smaller than the customer-directory volumes that
     * justified the same in-memory-merge approach in pos-customer PartyServiceImpl#browseParties
     * (ADR-0026 / OQ3).
     */
    private record Directory(
            List<Employee> employees,
            Map<UUID, Employee> employeesByPersonId,
            Map<UUID, ExtPersonReplica> replicasByPersonId) {}

    private Directory loadDirectory() {
        List<Employee> employees = employeeRepository.findAll();
        List<UUID> personIds = employees.stream().map(Employee::getPersonId).toList();
        Map<UUID, ExtPersonReplica> replicasByPersonId = extPersonReplicaRepository.findByPersonIdIn(personIds).stream()
                .collect(Collectors.toMap(ExtPersonReplica::getPersonId, Function.identity(), (a, b) -> a));
        // Reused by enrichWindow for jobRole (id -> Employee.jobRoleId) and by buildContactInfo
        // for contactInfo -- both already in memory from the two maps here, so building this
        // lookup costs a map insert per employee, not a query.
        Map<UUID, Employee> employeesByPersonId =
                employees.stream().collect(Collectors.toMap(Employee::getPersonId, Function.identity(), (a, b) -> a));
        return new Directory(employees, employeesByPersonId, replicasByPersonId);
    }

    /** Every employee in {@code directory} whose name/number matches {@code q}, as summaries. */
    private List<EmployeeSummaryDto> qFilteredSummaries(Directory directory, @Nullable String q) {
        return directory.employees().stream()
                .map(employee ->
                        toSummary(employee, directory.replicasByPersonId().get(employee.getPersonId())))
                .filter(summary -> matchesSearch(summary, q))
                .toList();
    }

    private EmployeeSummaryDto toSummary(Employee employee, @Nullable ExtPersonReplica person) {
        return EmployeeSummaryDto.builder()
                .employeeId(employee.getId())
                .personId(employee.getPersonId())
                .employeeNumber(employee.getEmployeeNumber())
                .firstName(person != null ? person.getFirstName() : null)
                .lastName(person != null ? person.getLastName() : null)
                .preferredName(person != null ? person.getPreferredName() : null)
                .status(employee.getStatus() != null ? employee.getStatus().name() : null)
                .active(employee.getStatus() == EmployeeStatus.ACTIVE)
                .build();
    }

    private static final Set<EmployeeSearchInclude> NO_INCLUDES = Set.of();

    /** Null/empty {@code include} means "the pre-#2155 thin row" -- see {@link #enrichWindow}. */
    private Set<EmployeeSearchInclude> resolveIncludes(@Nullable List<EmployeeSearchInclude> include) {
        return (include == null || include.isEmpty()) ? NO_INCLUDES : EnumSet.copyOf(include);
    }

    /**
     * Widens each row of the already-taken page window with the employee register's extra
     * columns (durion#2155): username, PII-gated contact info, active application roles, primary
     * location, and job role. One batched lookup per requested category, scoped to {@code
     * window}'s employees only -- this MUST be called with the page window taken by {@code
     * searchEmployees} (at most {@code size} rows), never with {@code all}/{@code filtered}/
     * {@code employees} there, which hold every employee the search matched (214+ and growing).
     * An enrichment batched against the full matched set instead of the window defeats the reason
     * #2155 exists: it turns a bounded, page-sized cost back into one that scales with tenant
     * size, exactly like the per-row calls this feature replaces. See the {@code findAll()}
     * comment in {@code searchEmployees} for the fuller warning.
     *
     * @param employeesByPersonId every employee the current search matched, keyed by person id
     *     (already in memory in {@code searchEmployees} -- reading {@code Employee.jobRoleId} off
     *     it below costs a map lookup, not a query)
     * @param replicasByPersonId every identity replica row the current search matched, keyed by
     *     person id (also already in memory -- {@code searchEmployees} loads it for name
     *     filtering/sorting, and {@code buildContactInfo} below is the same helper {@code
     *     getEmployee} uses to build the full-profile contact block)
     */
    private void enrichWindow(
            List<EmployeeSummaryDto> window,
            Set<EmployeeSearchInclude> includes,
            Map<UUID, Employee> employeesByPersonId,
            Map<UUID, ExtPersonReplica> replicasByPersonId) {
        if (window.isEmpty() || includes.isEmpty()) {
            return;
        }

        List<UUID> windowPersonIds =
                window.stream().map(EmployeeSummaryDto::getPersonId).toList();

        // Username backs both its own column and the join key role assignments are keyed by
        // (RoleAssignmentReplicaService takes usernames, not person ids) -- resolve it once
        // whenever either is requested rather than twice.
        boolean needsUsername = includes.contains(EmployeeSearchInclude.USERNAME)
                || includes.contains(EmployeeSearchInclude.ROLE_ASSIGNMENTS);
        Map<UUID, String> usernamesByPersonId =
                needsUsername ? personUsernameService.usernamesByPersonId(windowPersonIds) : Map.of();

        Map<String, List<EmployeeRoleAssignmentDto>> roleAssignmentsByUsername = includes.contains(
                        EmployeeSearchInclude.ROLE_ASSIGNMENTS)
                ? roleAssignmentReplicaService.findActiveRoleAssignmentsByUsernames(usernamesByPersonId.values())
                : Map.of();

        boolean includeContactInfo = includes.contains(EmployeeSearchInclude.CONTACT_INFO);
        // #1898: email/phone are gated on the narrow PII permission, never the structural
        // people:employee:view this whole endpoint already requires. A caller that requested
        // CONTACT_INFO but lacks people:employee_pii:view still gets 200 with the field simply
        // absent from every row -- never a 403 on a row, and never on the request.
        boolean callerHoldsPii =
                includeContactInfo && SecurityContextHelper.hasAuthority(PeoplePermissions.EMPLOYEE_PII_VIEW);

        boolean includeLocation = includes.contains(EmployeeSearchInclude.LOCATION);
        Map<UUID, List<EmployeeLocationAssignment>> activeAssignmentsByPersonId =
                includeLocation ? activeAssignmentsByPersonId(windowPersonIds) : Map.of();
        Map<UUID, String> locationNamesById =
                includeLocation ? locationNamesFor(activeAssignmentsByPersonId) : Map.of();

        boolean includeJobRole = includes.contains(EmployeeSearchInclude.JOB_ROLE);
        Map<UUID, JobRole> jobRolesById = includeJobRole ? jobRolesFor(window, employeesByPersonId) : Map.of();

        // durion#2159: unlike the categories above, this needs no batched lookup -- it is derived
        // purely from the caller's authorities (resolved once here, not per row) and each row's
        // own status, both already in hand. It still stays behind `include=` for consistency with
        // every other category on this endpoint; see EmployeeSearchInclude.ALLOWED_ACTIONS.
        boolean includeAllowedActions = includes.contains(EmployeeSearchInclude.ALLOWED_ACTIONS);
        Set<String> callerAuthoritiesForActions =
                includeAllowedActions ? employeeActionPolicy.currentCallerAuthorities() : Set.of();

        for (EmployeeSummaryDto row : window) {
            UUID personId = row.getPersonId();
            if (includes.contains(EmployeeSearchInclude.USERNAME)) {
                row.setUsername(usernamesByPersonId.get(personId));
            }
            if (includeContactInfo && callerHoldsPii) {
                row.setContactInfo(buildContactInfo(replicasByPersonId.get(personId)));
            }
            if (includes.contains(EmployeeSearchInclude.ROLE_ASSIGNMENTS)) {
                String username = usernamesByPersonId.get(personId);
                row.setRoleAssignments(
                        username == null ? List.of() : roleAssignmentsByUsername.getOrDefault(username, List.of()));
            }
            if (includeLocation) {
                applyLocation(row, activeAssignmentsByPersonId.getOrDefault(personId, List.of()), locationNamesById);
            }
            if (includeJobRole) {
                UUID jobRoleId = Optional.ofNullable(employeesByPersonId.get(personId))
                        .map(Employee::getJobRoleId)
                        .orElse(null);
                row.setJobRole(jobRoleId == null ? null : toJobRoleRef(jobRoleId, jobRolesById.get(jobRoleId)));
            }
            if (includeAllowedActions) {
                EmployeeStatus status = Optional.ofNullable(employeesByPersonId.get(personId))
                        .map(Employee::getStatus)
                        .orElse(null);
                row.setAllowedActions(employeeActionPolicy.allowedActions(status, callerAuthoritiesForActions));
            }
        }
    }

    /** {@code personId -> its active staffing assignments}, batched for {@code personIds}. */
    private Map<UUID, List<EmployeeLocationAssignment>> activeAssignmentsByPersonId(Collection<UUID> personIds) {
        List<EmployeeLocationAssignment> active =
                employeeLocationAssignmentRepository.findActiveByPersonIdIn(personIds, LocalDate.now(clock));
        Map<UUID, List<EmployeeLocationAssignment>> byPersonId = new LinkedHashMap<>();
        for (EmployeeLocationAssignment assignment : active) {
            byPersonId
                    .computeIfAbsent(assignment.getPersonId(), ignored -> new ArrayList<>())
                    .add(assignment);
        }
        return byPersonId;
    }

    /** Display names for every distinct location named by {@code assignmentsByPersonId}. */
    private Map<UUID, String> locationNamesFor(Map<UUID, List<EmployeeLocationAssignment>> assignmentsByPersonId) {
        Set<UUID> locationIds = assignmentsByPersonId.values().stream()
                .flatMap(List::stream)
                .map(EmployeeLocationAssignment::getLocationId)
                .collect(Collectors.toSet());
        return locationReferenceService.findLocationNames(locationIds);
    }

    /**
     * DECISION-PEOPLE-004: the register shows one primary location plus a count of the rest
     * ("Charlotte Main &middot; +1 more"), never the person's full assignment list. The primary
     * is whichever active assignment is flagged {@code isPrimary} -- {@link
     * StaffingAssignmentServiceImpl} enforces at most one active primary per person at a time --
     * and every other active assignment counts toward {@code otherLocationCount}, including the
     * defensive case where none is currently flagged primary (a lagging replica, or a person
     * mid-reassignment): the row then shows no primary location but still reports how many active
     * assignments exist, rather than guessing which one to call primary.
     */
    private void applyLocation(
            EmployeeSummaryDto row, List<EmployeeLocationAssignment> active, Map<UUID, String> locationNamesById) {
        Optional<EmployeeLocationAssignment> primary =
                active.stream().filter(EmployeeLocationAssignment::isPrimary).findFirst();
        row.setPrimaryLocation(primary.map(assignment -> EmployeeLocationDto.builder()
                        .id(assignment.getLocationId())
                        .name(locationNamesById.get(assignment.getLocationId()))
                        .build())
                .orElse(null));
        row.setOtherLocationCount(active.size() - (primary.isPresent() ? 1 : 0));
    }

    /** Job roles for every distinct {@code jobRoleId} named by {@code window}'s employees. */
    private Map<UUID, JobRole> jobRolesFor(List<EmployeeSummaryDto> window, Map<UUID, Employee> employeesByPersonId) {
        Set<UUID> jobRoleIds = window.stream()
                .map(row -> employeesByPersonId.get(row.getPersonId()))
                .filter(Objects::nonNull)
                .map(Employee::getJobRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (jobRoleIds.isEmpty()) {
            return Map.of();
        }
        return jobRoleRepository.findAllById(jobRoleIds).stream()
                .collect(Collectors.toMap(JobRole::getId, Function.identity(), (a, b) -> a));
    }

    /** Same degrade-to-id-alone behavior as {@link #buildJobRoleRef}, from a pre-fetched batch. */
    private EmployeeJobRoleDto toJobRoleRef(UUID jobRoleId, @Nullable JobRole jobRole) {
        return EmployeeJobRoleDto.builder()
                .id(jobRoleId)
                .code(jobRole != null ? jobRole.getCode() : null)
                .name(jobRole != null ? jobRole.getName() : null)
                .build();
    }

    private boolean matchesSearch(EmployeeSummaryDto summary, @Nullable String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(summary.getFirstName(), needle)
                || containsIgnoreCase(summary.getLastName(), needle)
                || containsIgnoreCase(summary.getPreferredName(), needle)
                || containsIgnoreCase(summary.getEmployeeNumber(), needle);
    }

    private boolean containsIgnoreCase(@Nullable String value, String lowercaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    /**
     * True when {@code summary} carries one of the requested statuses. A null or empty {@code
     * statuses} applies no filter (matches everything) — that is what makes an omitted {@code
     * status} query param mean "all statuses" rather than "no employees", and is what keeps a
     * pre-#2158 caller who never passed {@code status} seeing identical results.
     */
    private boolean matchesStatus(EmployeeSummaryDto summary, @Nullable List<EmployeeStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return true;
        }
        return statuses.stream().map(Enum::name).anyMatch(name -> name.equals(summary.getStatus()));
    }

    /** lastName, firstName, employeeNumber — null-safe, case-insensitive, nulls last. */
    private static final Comparator<EmployeeSummaryDto> SEARCH_COMPARATOR = Comparator.comparing(
                    EmployeeSummaryDto::getLastName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(EmployeeSummaryDto::getFirstName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(EmployeeSummaryDto::getEmployeeNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    /**
     * The reverse of {@link #SEARCH_COMPARATOR}, built field-by-field rather than via {@code
     * .reversed()} so that nulls stay LAST in both directions (a row with no name on file sorts
     * to the bottom of the register whichever way the column is sorted, instead of jumping to the
     * top on {@code desc} the way a bare {@code .reversed()} would put them).
     */
    private static final Comparator<EmployeeSummaryDto> SEARCH_COMPARATOR_DESC = Comparator.comparing(
                    EmployeeSummaryDto::getLastName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()))
            .thenComparing(
                    EmployeeSummaryDto::getFirstName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()))
            .thenComparing(
                    EmployeeSummaryDto::getEmployeeNumber,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()));

    private static final String DEFAULT_SORT = "lastName,asc";

    /**
     * Parses the {@code sort} query param ({@code "field[,direction]"}, the Spring Data
     * convention already used elsewhere in this backend, e.g. pos-accounting's
     * {@code SortParamParser}) into the comparator to apply. Only {@code lastName} is supported
     * today — the register's other columns (status, employee number) are not yet sortable — so
     * this stays a small hand-rolled switch rather than a generic field-name-to-accessor map; add
     * to it if/when another sortable column is requested. Null or blank defaults to {@code
     * "lastName,asc"}, matching the pre-#2158 unconditional {@code SEARCH_COMPARATOR} so an
     * existing caller that never passes {@code sort} sees unchanged ordering.
     */
    private Comparator<EmployeeSummaryDto> resolveComparator(@Nullable String sort) {
        String value = (sort == null || sort.isBlank()) ? DEFAULT_SORT : sort.trim();
        String[] parts = value.split(",", 2);
        String field = parts[0].trim();
        if (!"lastName".equalsIgnoreCase(field)) {
            throw new RequestValidationException("Unsupported sort field: '" + field + "'. Supported fields: lastName");
        }
        String direction = parts.length == 2 ? parts[1].trim() : "asc";
        if ("desc".equalsIgnoreCase(direction)) {
            return SEARCH_COMPARATOR_DESC;
        }
        if (!"asc".equalsIgnoreCase(direction)) {
            throw new RequestValidationException(
                    "Unsupported sort direction: '" + direction + "'. Use 'asc' or 'desc'.");
        }
        return SEARCH_COMPARATOR;
    }

    /** Queue the identity attributes as an upsert command toward pos-people-contact. */
    private void requestIdentityUpsert(
            UUID personId, String firstName, String lastName, String preferredName, EmployeeContactInfoDto contact) {
        peopleEventPublisher.requestPersonUpsert(new PersonUpsertRequestedV1(
                personId,
                normalize(firstName),
                normalize(lastName),
                normalize(preferredName),
                contact == null ? null : normalize(contact.getPrimaryEmail()),
                contact == null ? null : normalize(contact.getSecondaryEmail()),
                extractPhones(contact)));
    }

    private void validateEmployeeRequest(java.time.LocalDate hireDate, java.time.LocalDate terminationDate) {
        if (terminationDate != null && terminationDate.isBefore(hireDate)) {
            throw new SemanticValidationException("terminationDate must be greater than or equal to hireDate");
        }
    }

    private List<String> evaluateDuplicatePolicy(
            UUID employeeId,
            DuplicatePolicy duplicatePolicy,
            String employeeNumber,
            EmployeeContactInfoDto contactInfo,
            String firstName,
            String lastName) {
        DuplicatePolicy policy = duplicatePolicy != null ? duplicatePolicy : DuplicatePolicy.STRICT;

        DuplicateSignals duplicateSignals = collectDuplicateSignals(employeeId, employeeNumber, contactInfo);
        List<String> warnings = new ArrayList<>();
        if (policy == DuplicatePolicy.STRICT && duplicateSignals.hasAny()) {
            throw new IllegalStateException("Duplicate employee detected by STRICT policy");
        }

        if (policy != DuplicatePolicy.BALANCED) {
            return warnings;
        }

        if (duplicateSignals.hasAny()) {
            warnings.add("Potential duplicate detected; request accepted due to BALANCED duplicatePolicy");
        }

        if (hasAmbiguousNameMatch(employeeId, firstName, lastName)) {
            warnings.add("Ambiguous duplicate match detected by name similarity");
        }

        return warnings;
    }

    /**
     * Duplicate signals over the identity replica (email/phone) and local employment rows
     * (employee number). Replica-based checks are eventually consistent — a person written
     * moments ago may not be visible yet, which is acceptable for a warning/policy signal.
     */
    private DuplicateSignals collectDuplicateSignals(
            UUID employeeId, String employeeNumber, EmployeeContactInfoDto contactInfo) {
        String primaryEmail = contactInfo != null ? normalize(contactInfo.getPrimaryEmail()) : null;
        String primaryPhone = contactInfo != null ? normalize(contactInfo.getPrimaryPhone()) : null;
        String secondaryPhone = contactInfo != null ? normalize(contactInfo.getSecondaryPhone()) : null;

        boolean duplicateEmployeeNumber = employeeNumber != null
                && (employeeId == null
                        ? employeeRepository.existsByEmployeeNumberIgnoreCase(employeeNumber)
                        : employeeRepository.existsByEmployeeNumberIgnoreCaseAndPersonIdNot(
                                employeeNumber, employeeId));

        boolean duplicatePrimaryEmail = primaryEmail != null
                && extPersonReplicaRepository.findByPrimaryEmailIgnoreCase(primaryEmail).stream()
                        .anyMatch(person ->
                                employeeId == null || !person.getPersonId().equals(employeeId));

        boolean duplicatePhone = hasDuplicatePhone(employeeId, primaryPhone, secondaryPhone);
        return new DuplicateSignals(duplicateEmployeeNumber, duplicatePrimaryEmail, duplicatePhone);
    }

    private boolean hasAmbiguousNameMatch(UUID employeeId, String firstName, String lastName) {
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            return false;
        }
        return extPersonReplicaRepository.findByLastNameIgnoreCase(lastName).stream()
                .filter(person -> firstName.equalsIgnoreCase(person.getFirstName()))
                .anyMatch(person -> employeeId == null || !person.getPersonId().equals(employeeId));
    }

    /** Employment attributes on the Employee record. */
    private void applyEmployment(
            Employee employee,
            String employeeNumber,
            EmployeeStatus status,
            java.time.LocalDate hireDate,
            java.time.LocalDate terminationDate,
            @Nullable UUID jobRoleId) {
        employee.setEmployeeNumber(employeeNumber);
        employee.setStatus(status);
        employee.setHireDate(hireDate);
        employee.setTerminationDate(terminationDate);
        employee.setJobRoleId(jobRoleId);
        if (employee.getStatusEffectiveAt() == null) {
            employee.setStatusEffectiveAt(Instant.now(clock));
        }
    }

    /**
     * A non-null {@code jobRoleId} must already name a row on the tenant's job-role list
     * (durion#2157) -- HR master data the caller picks from {@code GET /v1/people/job-roles},
     * never a value it invents. {@code null} (no job role set) always passes.
     */
    private void requireJobRoleExists(@Nullable UUID jobRoleId) {
        if (jobRoleId != null && !jobRoleRepository.existsById(jobRoleId)) {
            throw new NotFoundException("Job role not found: " + jobRoleId);
        }
    }

    /** The nested job-role reference for a profile response, or null when none is set. */
    private @Nullable EmployeeJobRoleDto buildJobRoleRef(@Nullable UUID jobRoleId) {
        if (jobRoleId == null) {
            return null;
        }
        // requireJobRoleExists already rejected a create/update naming an unknown id, so a miss
        // here only happens for a row this read raced against; degrade to the id alone rather
        // than failing a read over a write that is someone else's problem to resolve.
        JobRole jobRole = jobRoleRepository.findById(jobRoleId).orElse(null);
        return EmployeeJobRoleDto.builder()
                .id(jobRoleId)
                .code(jobRole != null ? jobRole.getCode() : null)
                .name(jobRole != null ? jobRole.getName() : null)
                .build();
    }

    /** Primary + secondary phone from contact info, normalized and blank-filtered. */
    private List<String> extractPhones(EmployeeContactInfoDto contactInfo) {
        if (contactInfo == null) {
            return List.of();
        }
        return Stream.of(normalize(contactInfo.getPrimaryPhone()), normalize(contactInfo.getSecondaryPhone()))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private boolean hasDuplicatePhone(UUID employeeId, String primaryPhone, String secondaryPhone) {
        if (primaryPhone == null && secondaryPhone == null) {
            return false;
        }

        return hasDuplicatePhoneValue(employeeId, primaryPhone) || hasDuplicatePhoneValue(employeeId, secondaryPhone);
    }

    private boolean hasDuplicatePhoneValue(UUID employeeId, String phoneValue) {
        if (phoneValue == null || phoneValue.isBlank()) {
            return false;
        }
        return extPersonReplicaRepository.findByPrimaryPhoneOrSecondaryPhone(phoneValue, phoneValue).stream()
                .anyMatch(person -> employeeId == null || !person.getPersonId().equals(employeeId));
    }

    /** Profile for create/update responses: identity echoed from the request (replica may lag). */
    private EmployeeProfileDto profileFromRequestIdentity(
            UUID personId,
            String firstName,
            String lastName,
            String preferredName,
            EmployeeContactInfoDto contactInfo,
            Employee employee,
            List<String> warnings) {
        return EmployeeProfileDto.builder()
                .id(personId)
                .firstName(normalize(firstName))
                .lastName(normalize(lastName))
                .preferredName(normalize(preferredName))
                .employeeNumber(employee != null ? employee.getEmployeeNumber() : null)
                .status(employee != null ? employee.getStatus() : null)
                .hireDate(employee != null ? employee.getHireDate() : null)
                .terminationDate(employee != null ? employee.getTerminationDate() : null)
                .contactInfo(contactInfo)
                .jobRole(employee != null ? buildJobRoleRef(employee.getJobRoleId()) : null)
                .statusEffectiveAt(employee != null ? employee.getStatusEffectiveAt() : null)
                .createdAt(employee != null ? employee.getCreatedAt() : null)
                .updatedAt(employee != null ? employee.getUpdatedAt() : null)
                .warnings(warnings)
                .allowedActions(employeeActionPolicy.allowedActionsForCurrentCaller(
                        employee != null ? employee.getStatus() : null))
                .build();
    }

    /** Profile for reads: identity from the {@code ext_people_contact_person} replica. */
    private EmployeeProfileDto profileFromReplica(
            UUID personId, @Nullable ExtPersonReplica person, @Nullable Employee employee, List<String> warnings) {
        return EmployeeProfileDto.builder()
                .id(personId)
                .firstName(person != null ? person.getFirstName() : null)
                .lastName(person != null ? person.getLastName() : null)
                .preferredName(person != null ? person.getPreferredName() : null)
                .employeeNumber(employee != null ? employee.getEmployeeNumber() : null)
                .status(employee != null ? employee.getStatus() : null)
                .hireDate(employee != null ? employee.getHireDate() : null)
                .terminationDate(employee != null ? employee.getTerminationDate() : null)
                .contactInfo(buildContactInfo(person))
                .jobRole(employee != null ? buildJobRoleRef(employee.getJobRoleId()) : null)
                .statusEffectiveAt(employee != null ? employee.getStatusEffectiveAt() : null)
                .createdAt(
                        person != null
                                ? person.getPersonCreatedAt()
                                : (employee != null ? employee.getCreatedAt() : null))
                .updatedAt(
                        person != null
                                ? person.getPersonUpdatedAt()
                                : (employee != null ? employee.getUpdatedAt() : null))
                .warnings(warnings)
                .allowedActions(employeeActionPolicy.allowedActionsForCurrentCaller(
                        employee != null ? employee.getStatus() : null))
                .build();
    }

    /** Contact info from the replica's flattened contact columns. */
    private @Nullable EmployeeContactInfoDto buildContactInfo(@Nullable ExtPersonReplica person) {
        if (person == null) {
            return null;
        }
        String primaryEmail = normalize(person.getPrimaryEmail());
        String secondaryEmail = normalize(person.getSecondaryEmail());
        String primaryPhone = normalize(person.getPrimaryPhone());
        String secondaryPhone = normalize(person.getSecondaryPhone());

        if (primaryEmail == null && secondaryEmail == null && primaryPhone == null && secondaryPhone == null) {
            return null;
        }

        EmployeeContactInfoDto dto = new EmployeeContactInfoDto();
        dto.setPrimaryEmail(primaryEmail);
        dto.setSecondaryEmail(secondaryEmail);
        dto.setPrimaryPhone(primaryPhone);
        dto.setSecondaryPhone(secondaryPhone);
        return dto;
    }

    private void applyAssignmentPolicy(UUID employeeId, DisableEmployeeRequestDto request, String actorId) {
        AssignmentTerminationPolicy policy = request.getAssignmentPolicy() != null
                ? request.getAssignmentPolicy()
                : AssignmentTerminationPolicy.IMMEDIATE;

        switch (policy) {
            case IMMEDIATE ->
                log.info("Applying IMMEDIATE assignment offboarding for employee {} by actor {}", employeeId, actorId);
            case GRACE_PERIOD ->
                log.info(
                        "Applying GRACE_PERIOD assignment offboarding for employee {} with assignmentEndDate {} by actor {}",
                        employeeId,
                        request.getAssignmentEndDate(),
                        actorId);
            default -> throw new IllegalStateException("Unsupported assignment policy");
        }
    }

    private void queueOffboardingRetry(
            UUID employeeId, DisableEmployeeRequestDto request, String actorId, String failureReason) {
        EmployeeOffboardingRetry retry = new EmployeeOffboardingRetry();
        retry.setEmployeeId(employeeId);
        retry.setAssignmentPolicy(
                request.getAssignmentPolicy() != null
                        ? request.getAssignmentPolicy()
                        : AssignmentTerminationPolicy.IMMEDIATE);
        retry.setDisableReason(request.getDisableReason());
        retry.setActorId(actorId);
        retry.setFailureReason(failureReason != null ? failureReason : "unknown");
        retry.setAttempts(0);
        retry.setNextAttemptAt(Instant.now(clock).plusSeconds(300));
        offboardingRetryRepository.save(retry);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record DuplicateSignals(
            boolean duplicateEmployeeNumber, boolean duplicatePrimaryEmail, boolean duplicatePhone) {
        private boolean hasAny() {
            return duplicateEmployeeNumber || duplicatePrimaryEmail || duplicatePhone;
        }
    }
}
