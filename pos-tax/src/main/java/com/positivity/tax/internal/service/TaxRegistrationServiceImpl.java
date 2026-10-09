package com.positivity.tax.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.tax.TaxRegistrationChangedV1;
import com.positivity.shared.error.ApiError;
import com.positivity.tax.common.dto.TaxTypesResponse.RegimeEntry;
import com.positivity.tax.internal.config.OutboxEventWriter;
import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.dto.TaxRegistrationResponse;
import com.positivity.tax.internal.dto.TaxRegistrationUpdateRequest;
import com.positivity.tax.internal.entity.TaxRegistration;
import com.positivity.tax.internal.entity.TaxRegistrationHistory;
import com.positivity.tax.internal.enums.TaxRegistrationStatus;
import com.positivity.tax.internal.exception.TaxRegistrationConflictException;
import com.positivity.tax.internal.exception.TaxRegistrationNotFoundException;
import com.positivity.tax.internal.exception.TaxRequestInvalidException;
import com.positivity.tax.internal.repository.TaxRegistrationHistoryRepository;
import com.positivity.tax.internal.repository.TaxRegistrationRepository;
import com.positivity.tenancy.TenantContext;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TaxRegistrationService} (CAP:550 S32c; ADR-0071 §6-7, AW58). Country-agnostic: the country, the regime,
 * the shape and the jurisdiction all come from the configured profiles (S32a) and shapes (S32b); no country, regime
 * or jurisdiction is named here.
 *
 * <p>The number is checked with {@link RegistrationNumberShapes#wellFormed} first, and only a number it accepts is
 * normalised and stored ({@code normalize} refuses an over-long value, so it never sees unchecked input). A number is
 * never logged, echoed in an error or put in an exception message, accepted or not.
 *
 * <p>The actor is the request's principal, which {@code FrontDoorSecretFilter} bound from the forwarded {@code
 * X-User-Id} (ADR-0018); the tenant is the one {@code TenantContextFilter} bound from the forwarded {@code
 * X-Tenant-Id} (ADR-0062). Neither is ever read from the body.
 */
@Slf4j
@Service
public class TaxRegistrationServiceImpl implements TaxRegistrationService {

    static final int MIN_JUSTIFICATION = 10;
    static final int MAX_JUSTIFICATION = 1000;

    static final String SOURCE_SERVICE = "pos-tax";

    /** SQLSTATE of an exclusion-constraint violation: two concurrent writes that overlap. */
    static final String EXCLUSION_VIOLATION = "23P01";

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final String COUNTRY_CODE = "countryCode";
    private static final String REGIME = "regime";
    private static final String REGISTRATION_NUMBER = "registrationNumber";

    private final TaxRegistrationRepository registrations;
    private final TaxRegistrationHistoryRepository history;
    private final TaxCountryProfiles profiles;
    private final RegistrationNumberShapes shapes;
    private final OutboxEventWriter outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String eventsTopic;

    public TaxRegistrationServiceImpl(
            TaxRegistrationRepository registrations,
            TaxRegistrationHistoryRepository history,
            TaxCountryProfiles profiles,
            RegistrationNumberShapes shapes,
            OutboxEventWriter outbox,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${pos.tax.kafka.events-topic:tax.events.v1}") String eventsTopic) {
        this.registrations = registrations;
        this.history = history;
        this.profiles = profiles;
        this.shapes = shapes;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.eventsTopic = eventsTopic;
    }

    @Override
    @Transactional
    public @NonNull WriteResult create(@NonNull TaxRegistrationCreateRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (request.countryCode() == null
                || !COUNTRY.matcher(request.countryCode()).matches()) {
            errors.add(fieldError(COUNTRY_CODE, "is required and must be two upper-case letters"));
        }
        if (request.regime() == null || request.regime().isBlank()) {
            errors.add(fieldError(REGIME, "is required"));
        }
        requireCommon(
                request.registrationNumber(),
                request.effectiveFrom(),
                request.effectiveTo(),
                request.justification(),
                request.requestId(),
                errors);
        throwIfAny(errors);

        Optional<WriteResult> replay = replay(request.requestId());
        if (replay.isPresent()) {
            return replay.get();
        }

        String countryCode = request.countryCode();
        String regime = request.regime().trim();
        RegimeEntry declared = declaredRegime(countryCode, regime);
        String number = wellFormedNumber(regime, request.registrationNumber());
        requireNoOverlap(countryCode, regime, request.effectiveFrom(), request.effectiveTo(), null);

        String actor = actor();
        TaxRegistration registration = TaxRegistration.builder()
                .countryCode(countryCode)
                .regime(regime)
                .registrationNumber(number)
                .jurisdictionCode(jurisdictionOf(countryCode, declared))
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .createdBy(actor)
                .updatedBy(actor)
                .build();
        registration = flush(registration);
        record(registration, TaxRegistrationHistory.CREATE, null, request.justification(), request.requestId(), actor);
        log.info(
                "Tax registration {} created tenant={} country={} regime={} version={}",
                registration.getId(),
                registration.getTenantId(),
                countryCode,
                regime,
                registration.getVersion());
        return new WriteResult(toResponse(registration), false);
    }

    @Override
    @Transactional
    public @NonNull WriteResult update(@NonNull UUID registrationId, @NonNull TaxRegistrationUpdateRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        requireCommon(
                request.registrationNumber(),
                request.effectiveFrom(),
                request.effectiveTo(),
                request.justification(),
                request.requestId(),
                errors);
        if (request.version() == null || request.version() < 0) {
            errors.add(fieldError("version", "is required and must not be negative"));
        }
        throwIfAny(errors);

        Optional<WriteResult> replay = replay(request.requestId());
        if (replay.isPresent()) {
            return replay.get();
        }

        TaxRegistration registration = registrations
                .findById(registrationId)
                .orElseThrow(() -> new TaxRegistrationNotFoundException(registrationId));
        if (registration.getVersion() != request.version()) {
            throw TaxRegistrationConflictException.optimisticLock();
        }
        String number = wellFormedNumber(registration.getRegime(), request.registrationNumber());
        requireNoOverlap(
                registration.getCountryCode(),
                registration.getRegime(),
                request.effectiveFrom(),
                request.effectiveTo(),
                registration.getId());

        String actor = actor();
        String before = snapshot(registration);
        registration.setRegistrationNumber(number);
        registration.setEffectiveFrom(request.effectiveFrom());
        registration.setEffectiveTo(request.effectiveTo());
        registration.setUpdatedBy(actor);
        registration = flush(registration);
        record(
                registration,
                TaxRegistrationHistory.UPDATE,
                before,
                request.justification(),
                request.requestId(),
                actor);
        log.info(
                "Tax registration {} changed tenant={} country={} regime={} version={}",
                registration.getId(),
                registration.getTenantId(),
                registration.getCountryCode(),
                registration.getRegime(),
                registration.getVersion());
        return new WriteResult(toResponse(registration), false);
    }

    // ── checks ────────────────────────────────────────────────────────────────────────────────

    private static void requireCommon(
            @Nullable String number,
            @Nullable LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            @Nullable String justification,
            @Nullable UUID requestId,
            List<ApiError.FieldError> errors) {
        if (number == null || number.isBlank()) {
            errors.add(fieldError(REGISTRATION_NUMBER, "is required"));
        }
        if (effectiveFrom == null) {
            errors.add(fieldError("effectiveFrom", "is required"));
        } else if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            errors.add(fieldError("effectiveTo", "must not be before effectiveFrom"));
        }
        if (justification == null
                || justification.trim().length() < MIN_JUSTIFICATION
                || justification.trim().length() > MAX_JUSTIFICATION) {
            errors.add(fieldError(
                    "justification",
                    "is required and must be " + MIN_JUSTIFICATION + " to " + MAX_JUSTIFICATION + " characters"));
        }
        if (requestId == null) {
            errors.add(fieldError("requestId", "is required"));
        }
    }

    /** The regime as the country's profile declares it; a country without a profile or another regime is 400. */
    private RegimeEntry declaredRegime(String countryCode, String regime) {
        TaxCountryProfiles.CountryTaxProfile profile =
                profiles.profile(countryCode).orElseThrow(() -> invalid(COUNTRY_CODE, "has no tax profile configured"));
        return profile.regimes().stream()
                .filter(entry -> entry.regime().equals(regime))
                .findFirst()
                .orElseThrow(() -> invalid(REGIME, "is not a regime the country's tax profile declares"));
    }

    /**
     * The normalised number, only once {@code wellFormed} has accepted it. A refusal names the field and the rule;
     * the value appears nowhere.
     */
    private String wellFormedNumber(String regime, String number) {
        if (!shapes.wellFormed(regime, number)) {
            throw invalid(REGISTRATION_NUMBER, "does not match the regime's registration-number shape");
        }
        return RegistrationNumberShapes.normalize(number);
    }

    /**
     * At most one registration per tenant, country and regime is in effect on any date (both ends inclusive). The
     * exclusion constraint {@code tax_registration_no_overlap} is the backstop for two concurrent writes.
     */
    private void requireNoOverlap(
            String countryCode,
            String regime,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            @Nullable UUID except) {
        for (TaxRegistration other : registrations.findByCountryCodeAndRegime(countryCode, regime)) {
            if (!other.getId().equals(except)
                    && overlaps(effectiveFrom, effectiveTo, other.getEffectiveFrom(), other.getEffectiveTo())) {
                throw TaxRegistrationConflictException.overlap();
            }
        }
    }

    static boolean overlaps(LocalDate fromA, @Nullable LocalDate toA, LocalDate fromB, @Nullable LocalDate toB) {
        boolean aEndsBeforeB = toA != null && toA.isBefore(fromB);
        boolean bEndsBeforeA = toB != null && toB.isBefore(fromA);
        return !aEndsBeforeB && !bEndsBeforeA;
    }

    /** The regime's single region when it lists exactly one, else the country (never input). */
    static String jurisdictionOf(String countryCode, RegimeEntry regime) {
        List<String> regions = regime.regions();
        return regions != null && regions.size() == 1 ? regions.get(0) : countryCode;
    }

    // ── writes ────────────────────────────────────────────────────────────────────────────────

    /** Saves and flushes, so the version is the committed one and a concurrent overlap surfaces here. */
    private TaxRegistration flush(TaxRegistration registration) {
        try {
            return registrations.saveAndFlush(registration);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw TaxRegistrationConflictException.optimisticLock();
        } catch (DataIntegrityViolationException e) {
            if (EXCLUSION_VIOLATION.equals(sqlState(e))) {
                throw TaxRegistrationConflictException.overlap();
            }
            throw e;
        }
    }

    private void record(
            TaxRegistration registration,
            String changeType,
            @Nullable String before,
            String justification,
            UUID requestId,
            String actor) {
        Instant now = Instant.now(clock);
        history.save(TaxRegistrationHistory.builder()
                .registrationId(registration.getId())
                .requestId(requestId)
                .changeType(changeType)
                .oldState(before)
                .newState(snapshot(registration))
                .actor(actor)
                .justification(justification.trim())
                .recordedAt(now)
                .build());
        TaxRegistrationChangedV1 fact = new TaxRegistrationChangedV1(
                registration.getId(),
                TenantContext.require(),
                registration.getCountryCode(),
                registration.getRegime(),
                registration.getRegistrationNumber(),
                registration.getJurisdictionCode(),
                registration.getEffectiveFrom(),
                registration.getEffectiveTo(),
                statusOf(registration).name(),
                registration.getVersion(),
                now);
        outbox.publish(
                eventsTopic,
                DomainEventEnvelope.of(
                        TaxRegistrationChangedV1.EVENT_TYPE,
                        TaxRegistrationChangedV1.SCHEMA_VERSION,
                        registration.getId(),
                        registration.getVersion(),
                        SOURCE_SERVICE,
                        TenantContext.require(),
                        null,
                        actor,
                        fact,
                        clock));
    }

    private Optional<WriteResult> replay(UUID requestId) {
        return history.findByRequestId(requestId)
                .flatMap(change -> registrations.findById(change.getRegistrationId()))
                .map(registration -> new WriteResult(toResponse(registration), true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private TaxRegistrationResponse toResponse(TaxRegistration registration) {
        return new TaxRegistrationResponse(
                registration.getId(),
                registration.getCountryCode(),
                registration.getRegime(),
                registration.getRegistrationNumber(),
                registration.getJurisdictionCode(),
                registration.getEffectiveFrom(),
                registration.getEffectiveTo(),
                statusOf(registration).name(),
                registration.getVersion(),
                registration.getCreatedAt(),
                registration.getCreatedBy(),
                registration.getUpdatedAt(),
                registration.getUpdatedBy());
    }

    private TaxRegistrationStatus statusOf(TaxRegistration registration) {
        return TaxRegistrationStatus.on(
                registration.getEffectiveFrom(),
                registration.getEffectiveTo(),
                LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC));
    }

    /** A JSON snapshot of the registration for its history: old and new states. */
    private String snapshot(TaxRegistration registration) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("registrationNumber", registration.getRegistrationNumber());
        state.put("effectiveFrom", String.valueOf(registration.getEffectiveFrom()));
        state.put(
                "effectiveTo",
                registration.getEffectiveTo() == null
                        ? null
                        : registration.getEffectiveTo().toString());
        state.put("version", registration.getVersion());
        return objectMapper.writeValueAsString(state);
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new IllegalStateException("A tax-registration write needs the forwarded actor");
        }
        return authentication.getName();
    }

    private static @Nullable String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static ApiError.FieldError fieldError(String field, String rule) {
        return new ApiError.FieldError(field, field + " " + rule);
    }

    private static TaxRequestInvalidException invalid(String field, String rule) {
        return new TaxRequestInvalidException(List.of(fieldError(field, rule)));
    }

    private static void throwIfAny(List<ApiError.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new TaxRequestInvalidException(errors);
        }
    }
}
