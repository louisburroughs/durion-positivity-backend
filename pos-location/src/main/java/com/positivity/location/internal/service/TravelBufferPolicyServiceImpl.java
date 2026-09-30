package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API for travel buffer policy management.
 *
 * Issue: #76
 */
@Service
public class TravelBufferPolicyServiceImpl implements TravelBufferPolicyService {
    private static final String NOTES = "notes";

    private static final String BUFFER_VALUE = "bufferValue";

    private static final String BUFFER_TYPE = "bufferType";

    private static final String BUFFER_TYPE_INVALID = "bufferType must be FIXED_MINUTES or DISTANCE_TIER";

    private static final String FIXED_MINUTES_VALUE_INVALID = "bufferValue must be a whole number of minutes";

    private static final String FIXED_MINUTES = "FIXED_MINUTES";
    private static final String DISTANCE_TIER = "DISTANCE_TIER";

    private static final String TRAVEL_BUFFER_POLICY_NAME_TAKEN = "TRAVEL_BUFFER_POLICY_NAME_TAKEN";
    private static final String TRAVEL_BUFFER_POLICY_CONFLICT = "TRAVEL_BUFFER_POLICY_CONFLICT";

    /** The unique constraint on {@code (tenant_id, name)} in {@code V1__baseline_location.sql}. */
    private static final String NAME_UNIQUE_CONSTRAINT = "travel_buffer_policies_name_key";

    /** {@code travel_buffer_policies.name} and {@code .notes} are {@code varchar(255)}. */
    private static final int TEXT_COLUMN_MAX_LENGTH = 255;

    /**
     * DECISION-LOCATION-028 rule 5 / DECISION-LOCATION-015: the code's former {@code FLAT_MINUTES}
     * is renamed to {@code FIXED_MINUTES}; {@code PERCENTAGE_OF_TRAVEL} and {@code
     * DISTANCE_MULTIPLIER}, which no decision defines and which need routed travel time no service
     * provides, are removed.
     */
    static final Set<String> SUPPORTED_BUFFER_TYPES = Set.of(FIXED_MINUTES, DISTANCE_TIER);

    protected final TravelBufferPolicyRepository repository;

    public TravelBufferPolicyServiceImpl(TravelBufferPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a travel buffer policy from map payload.
     *
     * @param request payload map
     * @return created response
     */
    @Transactional
    public TravelBufferPolicyResponse create(Map<String, Object> request) {
        return create(toRequest(request));
    }

    /**
     * Creates a travel buffer policy from typed payload.
     *
     * @param request typed payload
     * @return created response
     */
    @Transactional
    public TravelBufferPolicyResponse create(TravelBufferPolicyRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw InvalidFieldException.invalid("name", "name is required and must not be blank");
        }
        String name = request.getName().trim();
        if (name.length() > TEXT_COLUMN_MAX_LENGTH) {
            throw InvalidFieldException.invalid(
                    "name", "name must be at most " + TEXT_COLUMN_MAX_LENGTH + " characters");
        }
        validateRequest(request.getBufferType(), request.getBufferValue(), true);

        TravelBufferPolicyEntity entity = TravelBufferPolicyEntity.builder()
                .name(name)
                .bufferType(request.getBufferType())
                .bufferValue(request.getBufferValue())
                .notes(requireNotes(request.getNotes()))
                .build();

        TravelBufferPolicyEntity saved = entity;
        if (repository != null) {
            try {
                // saveAndFlush, not save: save defers the INSERT to commit, past this catch, so a
                // duplicate name surfaced as an unmapped 500 instead of TRAVEL_BUFFER_POLICY_NAME_TAKEN.
                saved = repository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException exception) {
                throw toTravelBufferPolicyConflictException(exception);
            }
        }
        return toResponse(saved);
    }

    /**
     * Applies partial updates to a travel buffer policy.
     *
     * @param id    policy identifier
     * @param patch patch map
     * @return updated policy response
     */
    @Transactional
    public TravelBufferPolicyResponse patch(String id, Map<String, Object> patch) {
        UUID policyId = parseUuidStrict(id);
        TravelBufferPolicyEntity entity = repository
                .findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Travel buffer policy not found"));

        if (patch.containsKey(BUFFER_TYPE)) {
            Object bufferType = patch.get(BUFFER_TYPE);
            if (!(bufferType instanceof String text) || !SUPPORTED_BUFFER_TYPES.contains(text)) {
                throw InvalidFieldException.invalid(BUFFER_TYPE, BUFFER_TYPE_INVALID);
            }
            entity.setBufferType(text);
        }
        if (patch.containsKey(BUFFER_VALUE)) {
            Object bufferValue = patch.get(BUFFER_VALUE);
            BigDecimal parsed = parseBigDecimal(bufferValue);
            if (bufferValue != null && parsed == null) {
                throw InvalidFieldException.invalid(BUFFER_VALUE, "bufferValue must be a number");
            }
            entity.setBufferValue(parsed);
        }
        if (patch.containsKey(NOTES)) {
            entity.setNotes(requireNotes(patch.get(NOTES)));
        }

        validateRequest(entity.getBufferType(), entity.getBufferValue(), false);

        TravelBufferPolicyEntity saved = entity;
        if (repository != null) {
            try {
                saved = repository.saveAndFlush(entity);
            } catch (DataIntegrityViolationException exception) {
                throw toTravelBufferPolicyConflictException(exception);
            }
        }
        return toResponse(saved);
    }

    /**
     * Lists policies.
     *
     * @return all policy responses
     */
    @Transactional(readOnly = true)
    public List<TravelBufferPolicyResponse> list() {
        if (repository == null) {
            return List.of();
        }
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    /** 400 {@code VALIDATION_ERROR} naming the field, rather than an unmapped 500 (#2252). */
    private void validateRequest(String bufferType, BigDecimal bufferValue, boolean requiredType) {
        if (requiredType && bufferType == null) {
            throw InvalidFieldException.invalid(BUFFER_TYPE, BUFFER_TYPE_INVALID);
        }
        if (bufferType != null && !SUPPORTED_BUFFER_TYPES.contains(bufferType)) {
            throw InvalidFieldException.invalid(BUFFER_TYPE, BUFFER_TYPE_INVALID);
        }
        if (bufferValue != null && bufferValue.signum() < 0) {
            throw InvalidFieldException.invalid(BUFFER_VALUE, "bufferValue must be non-negative");
        }
        // DECISION-LOCATION-015: FIXED_MINUTES is { minutes: int >= 0 } — bufferValue carries that
        // count and must be a whole number, never a fraction of a minute.
        if (FIXED_MINUTES.equals(bufferType)
                && bufferValue != null
                && bufferValue.stripTrailingZeros().scale() > 0) {
            throw InvalidFieldException.invalid(BUFFER_VALUE, FIXED_MINUTES_VALUE_INVALID);
        }
    }

    /**
     * Text of at most the column width, or null to clear. The column is {@code varchar(255)}, so a
     * longer value would fail at flush as a Postgres 22001, which is a length error and not a
     * conflict: refuse it here as a 400 naming the field instead.
     */
    private static String requireNotes(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw InvalidFieldException.invalid(NOTES, "notes must be text or null");
        }
        if (text.length() > TEXT_COLUMN_MAX_LENGTH) {
            throw InvalidFieldException.invalid(
                    NOTES, "notes must be at most " + TEXT_COLUMN_MAX_LENGTH + " characters");
        }
        return text;
    }

    private TravelBufferPolicyRequest toRequest(Map<String, Object> map) {
        return TravelBufferPolicyRequest.builder()
                .name((String) map.get("name"))
                .bufferType(map.get(BUFFER_TYPE) == null ? null : String.valueOf(map.get(BUFFER_TYPE)))
                .bufferValue(parseBigDecimal(map.get(BUFFER_VALUE)))
                .notes((String) map.get(NOTES))
                .build();
    }

    private TravelBufferPolicyResponse toResponse(TravelBufferPolicyEntity entity) {
        return TravelBufferPolicyResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .bufferType(entity.getBufferType())
                .bufferValue(entity.getBufferValue())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
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
    private RuntimeException toTravelBufferPolicyConflictException(DataIntegrityViolationException exception) {
        if (!exception.contains(ConstraintViolationException.class)) {
            return exception;
        }
        if (isNameConstraintViolation(exception)) {
            return new DuplicateResourceException(TRAVEL_BUFFER_POLICY_NAME_TAKEN);
        }
        return new DuplicateResourceException(TRAVEL_BUFFER_POLICY_CONFLICT);
    }

    /**
     * True only for the {@code (tenant_id, name)} unique constraint. Matching the table name or a
     * loose " name " would report any other violation on {@code travel_buffer_policies} (a bufferType
     * check, say) as a name clash.
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
                all.append(message.toLowerCase(java.util.Locale.ROOT)).append(' ');
            }
            cursor = cursor.getCause();
        }
        return all.toString();
    }
}
