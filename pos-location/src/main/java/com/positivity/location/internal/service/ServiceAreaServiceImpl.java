package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.ServiceAreaPostalCodesRequest;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API for service area management.
 *
 * Issue: #76
 */
@Service
public class ServiceAreaServiceImpl implements ServiceAreaService {

    private static final String SERVICE_AREA_NAME_TAKEN = "SERVICE_AREA_NAME_TAKEN";
    private static final String SERVICE_AREA_CONFLICT = "SERVICE_AREA_CONFLICT";
    private static final String NAME = "name";
    private static final String ACTIVE = "active";
    private static final String DESCRIPTION = "description";

    /** {@code service_areas.name} and {@code .description} are {@code varchar(255)}. */
    private static final int TEXT_COLUMN_MAX_LENGTH = 255;

    /** The unique constraint on {@code (tenant_id, name)} in {@code V1__baseline_location.sql}. */
    private static final String NAME_UNIQUE_CONSTRAINT = "service_areas_name_key";

    protected final ServiceAreaRepository serviceAreaRepository;

    public ServiceAreaServiceImpl(ServiceAreaRepository serviceAreaRepository) {
        this.serviceAreaRepository = serviceAreaRepository;
    }

    /**
     * Creates a service area from a typed request.
     *
     * @param request typed payload
     * @return created service area response
     */
    @Transactional
    public ServiceAreaResponse create(ServiceAreaRequest request) {
        validatePostalCodes(request.getPostalCodes());

        ServiceAreaEntity entity = ServiceAreaEntity.builder()
                .name(requireName(request.getName()))
                .description(request.getDescription())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .postalCodes(toPostalValues(request.getPostalCodes()))
                .build();

        return toResponse(saveAndFlush(entity));
    }

    /**
     * Patches a service area.
     *
     * <p>Reads three keys, each strictly typed so a malformed value is a 400 naming the field and
     * never a silent coercion or a ClassCastException: {@code name} (non-blank text, renames the
     * area, 409 {@code SERVICE_AREA_NAME_TAKEN} when another area holds it), {@code description}
     * (text, or JSON null to clear it) and {@code active} (a JSON boolean only). An absent key
     * leaves that field unchanged; other keys are ignored. Every key is validated before any is
     * applied, so a refused patch changes nothing.
     *
     * @param id    service area identifier
     * @param patch map payload
     * @return updated service area response
     */
    @Transactional
    public ServiceAreaResponse patch(String id, Map<String, Object> patch) {
        UUID areaId = parseUuidStrict(id);
        ServiceAreaEntity entity = serviceAreaRepository
                .findById(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("Service area not found"));

        String name = patch.containsKey(NAME) ? requireName(patch.get(NAME)) : null;
        boolean hasDescription = patch.containsKey(DESCRIPTION);
        String description = hasDescription ? requireDescription(patch.get(DESCRIPTION)) : null;
        Boolean active = patch.containsKey(ACTIVE) ? requireActive(patch.get(ACTIVE)) : null;

        if (name != null) {
            entity.setName(name);
        }
        if (hasDescription) {
            entity.setDescription(description);
        }
        if (active != null) {
            entity.setActive(active);
        }
        return toResponse(saveAndFlush(entity));
    }

    /**
     * Lists all service areas.
     *
     * @return service area responses
     */
    @Transactional(readOnly = true)
    public List<ServiceAreaResponse> list() {
        if (serviceAreaRepository == null) {
            return List.of();
        }
        return serviceAreaRepository.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * Replaces a service area's whole postal code set.
     *
     * <p>Postal codes were write-once until #1991: only createServiceArea accepted them, patch reads
     * just description and active, and there is no delete. A coverage area was therefore permanent
     * and unamendable, which no real shop is — coverage grows, shrinks, and gets typed wrong the
     * first time. Replacement rather than merge because that is what the geography is: the set sent
     * here is what the area covers afterwards.
     *
     * <p>Emptying the set is refused for the same reason createServiceArea refuses it. An area
     * covering nothing matches no address, and because findEligibleCoverageRules inner-joins these
     * rows, every coverage rule pointing at it would silently stop resolving. Retiring an area is
     * what {@code active=false} is for.
     *
     * @param id      service area identifier
     * @param request the complete replacement set
     * @return the area as it stands after the replacement
     */
    @Override
    @Transactional
    public @NonNull ServiceAreaResponse replacePostalCodes(
            @NonNull String id, @NonNull ServiceAreaPostalCodesRequest request) {
        UUID areaId = parseUuidStrict(id);
        ServiceAreaEntity entity = serviceAreaRepository
                .findById(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("Service area not found"));

        List<ServiceAreaRequest.PostalCodeEntry> replacement = request.getPostalCodes();
        validatePostalCodes(replacement);

        // Mutated in place rather than assigned: postalCodes is an @ElementCollection, and Hibernate
        // tracks the collection instance it loaded. Swapping the reference makes it drop every row
        // and reinsert; clearing and refilling lets it write only the difference.
        Set<ServiceAreaPostalCodeValue> current = entity.getPostalCodes();
        if (current == null) {
            entity.setPostalCodes(toPostalValues(replacement));
        } else {
            current.clear();
            current.addAll(toPostalValues(replacement));
        }

        return toResponse(saveAndFlush(entity));
    }

    /**
     * Writes the area and forces the INSERT/UPDATE now. A plain {@code save} defers the statement to
     * commit, after this method has returned, so a unique-name violation surfaced as an unmapped 500
     * instead of the {@code SERVICE_AREA_NAME_TAKEN} the catch below builds.
     */
    private ServiceAreaEntity saveAndFlush(ServiceAreaEntity entity) {
        try {
            return serviceAreaRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw toServiceAreaConflictException(exception);
        }
    }

    private static String requireName(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw InvalidFieldException.invalid(NAME, "name must be non-blank text");
        }
        String trimmed = text.trim();
        if (trimmed.length() > TEXT_COLUMN_MAX_LENGTH) {
            throw InvalidFieldException.invalid(NAME, "name must be at most " + TEXT_COLUMN_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String requireDescription(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw InvalidFieldException.invalid(DESCRIPTION, "description must be text or null");
        }
        if (text.length() > TEXT_COLUMN_MAX_LENGTH) {
            throw InvalidFieldException.invalid(
                    DESCRIPTION, "description must be at most " + TEXT_COLUMN_MAX_LENGTH + " characters");
        }
        return text;
    }

    private static Boolean requireActive(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw InvalidFieldException.invalid(ACTIVE, "active must be true or false");
    }

    private void validatePostalCodes(List<ServiceAreaRequest.PostalCodeEntry> postalCodes) {
        if (postalCodes == null || postalCodes.isEmpty()) {
            throw badRequest("service area must include at least one postal code");
        }
        if (postalCodes.stream().anyMatch(Objects::isNull)) {
            throw badRequest("postal code entries must not be null");
        }
        boolean missingCountryCode = postalCodes.stream()
                .anyMatch(entry ->
                        entry.getCountryCode() == null || entry.getCountryCode().isBlank());
        if (missingCountryCode) {
            throw badRequest("postal code entries require countryCode");
        }
    }

    private Set<ServiceAreaPostalCodeValue> toPostalValues(List<ServiceAreaRequest.PostalCodeEntry> postalCodes) {
        Set<ServiceAreaPostalCodeValue> values = new LinkedHashSet<>();
        if (postalCodes == null) {
            return values;
        }
        for (ServiceAreaRequest.PostalCodeEntry postalCode : postalCodes) {
            values.add(ServiceAreaPostalCodeValue.builder()
                    .postalCode(postalCode.getPostalCode())
                    .countryCode(postalCode.getCountryCode())
                    .build());
        }
        return values;
    }

    private ServiceAreaResponse toResponse(ServiceAreaEntity entity) {
        List<ServiceAreaRequest.PostalCodeEntry> postalCodes = entity.getPostalCodes() == null
                ? List.of()
                : entity.getPostalCodes().stream()
                        .map(value -> ServiceAreaRequest.PostalCodeEntry.builder()
                                .postalCode(value.getPostalCode())
                                .countryCode(value.getCountryCode())
                                .build())
                        .toList();

        return ServiceAreaResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .active(entity.getActive())
                .postalCodes(postalCodes)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * A rejection the error envelope renders as 400.
     *
     * <p>These checks used to throw a bare IllegalArgumentException, which GlobalApiExceptionHandler
     * has no handler for: it fell through to the catch-all and became a 500, for input both
     * createServiceArea and replaceServiceAreaPostalCodes document as a 400. ResponseStatusException
     * is what parseUuidStrict below already uses for the same purpose.
     */
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private UUID parseUuidStrict(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id: " + id, exception);
        }
    }

    /**
     * A 409 only for a constraint violation. Spring translates every integrity failure, a value too
     * long for its column (Postgres 22001, Hibernate {@code DataException}) included, to
     * DataIntegrityViolationException; only one whose cause chain holds a Hibernate {@link
     * ConstraintViolationException} is a conflict, so anything else is rethrown untouched.
     */
    private RuntimeException toServiceAreaConflictException(DataIntegrityViolationException exception) {
        if (!exception.contains(ConstraintViolationException.class)) {
            return exception;
        }
        if (isNameConstraintViolation(exception)) {
            return new DuplicateResourceException(SERVICE_AREA_NAME_TAKEN);
        }
        return new DuplicateResourceException(SERVICE_AREA_CONFLICT);
    }

    /**
     * True only for the {@code (tenant_id, name)} unique constraint. Matching the table name or a
     * loose " name " would report any other violation on {@code service_areas} as a name clash.
     */
    private boolean isNameConstraintViolation(Throwable throwable) {
        return lowerCaseMessages(throwable).contains(NAME_UNIQUE_CONSTRAINT);
    }

    private String lowerCaseMessages(Throwable throwable) {
        StringBuilder all = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                all.append(message.toLowerCase(Locale.ROOT)).append(' ');
            }
            cursor = cursor.getCause();
        }
        return all.toString();
    }
}
