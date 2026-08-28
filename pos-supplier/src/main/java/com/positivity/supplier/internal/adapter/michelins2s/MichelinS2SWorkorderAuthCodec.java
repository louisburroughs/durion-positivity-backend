package com.positivity.supplier.internal.adapter.michelins2s;

import com.positivity.supplier.internal.domain.model.FleetContract;
import com.positivity.supplier.internal.domain.model.FleetPolicy;
import com.positivity.supplier.internal.domain.model.FleetVehicle;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Michelin S2S workorder authorization codec (CAP-323 #1229) — the second protocol family.
 *
 * <h2>Transport-free, like every other codec</h2>
 *
 * This class builds request specs and reads bodies. It performs no I/O, holds no vendor connection
 * and knows nothing about polling: the fact that a 202 must be followed up is expressed by returning
 * a {@code PENDING} authorization carrying the {@code Location}, and acting on that is the runner's
 * job (ADR-0051 §5).
 *
 * <h2>Reading a decision from an underspecified response</h2>
 *
 * The source spec gives no response schema for the workorder operations, so the decoder accepts
 * several plausible field names for the same thing rather than committing to one. That is not
 * defensive padding: picking a single name and being wrong produces a <em>granted authorization with
 * no vendor id</em>, which looks fine for months and then cannot be approved when the work
 * completes. Reading whichever of the candidates is present costs nothing and removes that failure.
 *
 * <p>By the same reasoning an unrecognised status word is never treated as a grant. Work proceeding
 * on a misread word is the one outcome that cannot be undone by fixing the codec afterwards.
 */
@Component
@RequiredArgsConstructor
public class MichelinS2SWorkorderAuthCodec implements SupplierAdapterCodec {
    private static final String APPLICATION_JSON = "application/json";

    /**
     * Vendor words that mean the fleet said yes.
     *
     * <p>An allowlist, matched case-insensitively. Anything not in it is not a grant — see the class
     * note: the asymmetry is deliberate, because a false grant authorises real work and a false
     * non-grant merely asks again.
     */
    private static final Set<String> GRANTED_WORDS =
            Set.of("granted", "approved", "authorized", "authorised", "accepted");

    /** Vendor words that mean the fleet said no. A real answer, and a normal one. */
    private static final Set<String> DENIED_WORDS = Set.of("denied", "refused", "rejected", "declined");

    /** Vendor words that mean it does not know what we are talking about. */
    private static final Set<String> NOT_FOUND_WORDS = Set.of("notfound", "not_found", "unknown");

    /** Vendor words that mean "still deciding". */
    private static final Set<String> PENDING_WORDS =
            Set.of("pending", "inprogress", "in_progress", "processing", "accepted_pending");

    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.WORKORDER_AUTHORIZATION;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.MICHELIN_S2S;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.S2S_V1;
    }

    /**
     * Builds the authorization request.
     *
     * @param request  what is being asked for
     * @param partnerId the service-provider identity the vendor uses to contextualise the request,
     *                  from profile configuration. Required by the spec
     * @param apiMajorVersion major version for the {@code API-Version} header, per the spec's rule
     *                        that it carry the MAJOR version only and match the URI version
     * @return a transport-free request spec
     */
    @NonNull
    public SupplierRequestSpec buildAuthorizationRequest(
            @NonNull WorkorderAuthorizationRequest request,
            @NonNull String partnerId,
            @NonNull String apiMajorVersion) {
        List<WorkorderS2SWire.WorkorderRequestLine> lines = new ArrayList<>();
        for (WorkorderAuthorizationRequest.Line line : request.lines()) {
            lines.add(new WorkorderS2SWire.WorkorderRequestLine(
                    line.reference(), line.description(), line.quantity(), line.tirePosition()));
        }

        WorkorderS2SWire.WorkorderRequest body = new WorkorderS2SWire.WorkorderRequest(
                request.workorderId().toString(),
                request.vendorVehicleId(),
                request.licensePlate(),
                request.vin(),
                request.fleetNumber(),
                request.contractId(),
                request.odometerValue(),
                lines.isEmpty() ? null : lines);

        // Not idempotent: creating an authorization is a commercial act at the fleet, and a blind
        // re-send after an ambiguous failure could open a second authorization against the same
        // contract. Recovery goes through the stored row, not through re-dispatch (ADR-0052 §5).
        return new SupplierRequestSpec(
                "POST",
                "/api/v1/workOrders",
                partnerQuery(partnerId),
                objectMapper.writeValueAsString(body),
                APPLICATION_JSON,
                APPLICATION_JSON,
                false,
                apiVersionHeader(apiMajorVersion));
    }

    /**
     * Builds the completion sign-off.
     *
     * @param vendorAuthorizationId the vendor's own id for the authorization being approved
     */
    @NonNull
    public SupplierRequestSpec buildApprovalRequest(
            @NonNull String vendorAuthorizationId, @NonNull String partnerId, @NonNull String apiMajorVersion) {
        // Idempotent: approving an already-approved workorder is the vendor restating a fact, so a
        // retry after an ambiguous failure is safe — and it must be, because the alternative to
        // retrying is work performed that the fleet never agreed to pay for.
        return new SupplierRequestSpec(
                "PATCH",
                "/api/v1/workOrders/" + encodePathSegment(vendorAuthorizationId) + "/approve",
                partnerQuery(partnerId),
                "{}",
                APPLICATION_JSON,
                APPLICATION_JSON,
                true,
                apiVersionHeader(apiMajorVersion));
    }

    /** Builds a re-read of an accepted-but-undecided authorization, from the 202's {@code Location}. */
    @NonNull
    public SupplierRequestSpec buildPollRequest(@NonNull String pollPath, @NonNull String partnerId) {
        return new SupplierRequestSpec("GET", pollPath, partnerQuery(partnerId), null, null, APPLICATION_JSON, true);
    }

    /** Builds the vehicle lookup. */
    @NonNull
    public SupplierRequestSpec buildVehicleLookup(@NonNull String vehicleIdent, @NonNull String partnerId) {
        return new SupplierRequestSpec(
                "GET",
                "/api/v1/vehicles/" + encodePathSegment(vehicleIdent),
                partnerQuery(partnerId),
                null,
                null,
                APPLICATION_JSON,
                true);
    }

    /** Builds the contract lookup. */
    @NonNull
    public SupplierRequestSpec buildContractLookup(@NonNull String partnerId) {
        return new SupplierRequestSpec(
                "GET", "/api/v1/contracts", partnerQuery(partnerId), null, null, APPLICATION_JSON, true);
    }

    /** Builds the policy lookup. */
    @NonNull
    public SupplierRequestSpec buildPolicyLookup(@NonNull String partnerId) {
        return new SupplierRequestSpec(
                "GET", "/api/v1/policies", partnerQuery(partnerId), null, null, APPLICATION_JSON, true);
    }

    /**
     * Reads an authorization decision.
     *
     * @param body         response body as received; may be absent on a 202, which carries its answer
     *                     in the {@code Location} header instead
     * @param httpStatus   the status the vendor answered with
     * @param location     the {@code Location} header, where the vendor sent one
     * @param workorderId  this side's key for the job, carried through because the vendor's echo of
     *                     it cannot be relied on
     * @return the decision, or a {@code PENDING} authorization when the vendor accepted and will
     *     answer later
     * @throws WorkorderAuthDecodeException when the body is present but unreadable, or the vendor
     *     answered a status this codec cannot interpret
     */
    @NonNull
    public SupplierWorkorderAuthorization decodeDecision(
            @Nullable String body, int httpStatus, @Nullable String location, @NonNull UUID workorderId) {
        if (httpStatus == 202) {
            // Accepted, undecided. The Location is the whole content of the answer: without it there
            // is nothing to come back to, which is a vendor defect and must not read as "pending
            // forever".
            if (location == null || location.isBlank()) {
                throw new WorkorderAuthDecodeException(
                        "vendor accepted the authorization (HTTP 202) without a Location header, so its decision can never be read");
            }
            return SupplierWorkorderAuthorization.pending(workorderId, location);
        }

        if (body == null || body.isBlank()) {
            // A 201 with no body is not a grant. The vendor created something and told us nothing
            // about it; if a Location was given we can still go and read it.
            if (location != null && !location.isBlank()) {
                return SupplierWorkorderAuthorization.pending(workorderId, location);
            }
            throw new WorkorderAuthDecodeException("vendor answered HTTP " + httpStatus
                    + " with neither a body nor a Location, so no decision can be read");
        }

        WorkorderS2SWire.WorkorderDecision decision;
        try {
            decision = objectMapper.readValue(body, WorkorderS2SWire.WorkorderDecision.class);
        } catch (RuntimeException e) {
            throw new WorkorderAuthDecodeException(
                    "authorization response was not readable JSON: " + e.getMessage(), e);
        }

        String vendorId = firstPresent(decision.id(), decision.workOrderId());
        String statusWord = firstPresent(decision.status(), decision.state());
        SupplierWorkorderAuthorization.Status status = classify(statusWord, decision, location);

        if (status == SupplierWorkorderAuthorization.Status.PENDING) {
            return new SupplierWorkorderAuthorization(
                    vendorId,
                    workorderId,
                    SupplierWorkorderAuthorization.Status.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    location);
        }

        BigDecimal amount = decimalOrNull(decision.authorizedAmount());
        String currency = decision.currency();
        if (amount != null && (currency == null || currency.isBlank())) {
            // An amount the vendor gave no currency for is not a ceiling anybody can act on, and
            // guessing the profile's currency here would invent a commercial term.
            amount = null;
            currency = null;
        }

        return new SupplierWorkorderAuthorization(
                vendorId,
                workorderId,
                status,
                firstPresent(decision.reason(), decision.message(), firstErrorDetail(decision)),
                firstPresent(decision.reasonCode(), firstErrorCode(decision)),
                firstPresent(decision.contractReference(), decision.contractId()),
                amount,
                amount == null ? null : currency,
                null);
    }

    /** Reads a vehicle lookup response. */
    @NonNull
    public FleetVehicle decodeVehicle(@Nullable String body) {
        WorkorderS2SWire.Vehicle vehicle = read(body, WorkorderS2SWire.Vehicle.class, "vehicle");
        return new FleetVehicle(
                vehicle.id(),
                vehicle.licensePlate(),
                vehicle.fleetNumber(),
                vehicle.vin(),
                vehicle.brand(),
                vehicle.model(),
                vehicle.year(),
                vehicle.odometerValue());
    }

    /** Reads a contract lookup response. Both the v1 and v2.0.0 endpoints answer with this shape. */
    @NonNull
    public List<FleetContract> decodeContracts(@Nullable String body) {
        List<WorkorderS2SWire.ContractDetails> wire =
                readList(body, new TypeReference<List<WorkorderS2SWire.ContractDetails>>() {}, "contracts");
        List<FleetContract> contracts = new ArrayList<>(wire.size());
        for (WorkorderS2SWire.ContractDetails details : wire) {
            contracts.add(new FleetContract(
                    details.id(),
                    details.name(),
                    details.contractor() == null ? null : details.contractor().id(),
                    details.contractor() == null ? null : details.contractor().name(),
                    dateOrNull(details.startDate()),
                    dateOrNull(details.endDate())));
        }
        return List.copyOf(contracts);
    }

    /**
     * Reads a policy lookup response.
     *
     * <p>The spec gives {@code /policies} a bare {@code 200} with no schema, so this reads the
     * black/white list vocabulary the spec defines elsewhere and returns an empty policy rather than
     * throwing when it finds something else. A policy is advisory — failing a quote because an
     * advisory read came back in an unexpected shape would be the wrong trade.
     */
    @NonNull
    public List<FleetPolicy> decodePolicies(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw =
                    objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
            List<FleetPolicy> policies = new ArrayList<>(raw.size());
            for (Map<String, Object> entry : raw) {
                policies.add(new FleetPolicy(
                        stringOrNull(entry.get("id")),
                        stringOrNull(entry.get("name")),
                        Boolean.TRUE.equals(entry.get("useBlacklist")),
                        Boolean.TRUE.equals(entry.get("useWhiteList")),
                        references(entry.get("blackList")),
                        references(entry.get("whiteList"))));
            }
            return List.copyOf(policies);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Maps a vendor status word onto a decision.
     *
     * <p>Unrecognised words raise rather than defaulting. There is no safe default: defaulting to
     * granted authorises work nobody agreed to pay for, and defaulting to denied turns away a paying
     * fleet customer. Raising puts the row in front of a human, which is the only correct answer to
     * "the vendor said something we do not understand".
     */
    private SupplierWorkorderAuthorization.@NonNull Status classify(
            @Nullable String statusWord,
            WorkorderS2SWire.@NonNull WorkorderDecision decision,
            @Nullable String location) {
        if (statusWord == null || statusWord.isBlank()) {
            // No status word at all. An error list means refusal; anything else is undecided, and
            // undecided is only actionable if we were told where to look.
            if (decision.errors() != null && !decision.errors().isEmpty()) {
                return SupplierWorkorderAuthorization.Status.DENIED;
            }
            if (location != null && !location.isBlank()) {
                return SupplierWorkorderAuthorization.Status.PENDING;
            }
            throw new WorkorderAuthDecodeException(
                    "authorization response carried no status and no error, so the vendor's position is unknown");
        }

        String normalised = statusWord.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (GRANTED_WORDS.contains(normalised)) {
            return SupplierWorkorderAuthorization.Status.GRANTED;
        }
        if (DENIED_WORDS.contains(normalised)) {
            return SupplierWorkorderAuthorization.Status.DENIED;
        }
        if (NOT_FOUND_WORDS.contains(normalised.replace(" ", ""))) {
            return SupplierWorkorderAuthorization.Status.NOT_FOUND;
        }
        if (PENDING_WORDS.contains(normalised)) {
            return SupplierWorkorderAuthorization.Status.PENDING;
        }
        throw new WorkorderAuthDecodeException("vendor answered with an unrecognised authorization status '"
                + statusWord + "'; refusing to guess whether work is authorized");
    }

    /**
     * The {@code API-Version} header, which the spec says carries the MAJOR version only and must
     * match the URI version.
     *
     * <p>Sent so a vendor that has moved to a major version this codec was not written against
     * says so, rather than answering a v2 body to a v1 caller that will misread it. Omitted when
     * unconfigured rather than defaulted, because guessing a version is how that mismatch happens.
     */
    @NonNull
    private static Map<String, String> apiVersionHeader(@NonNull String apiMajorVersion) {
        return apiMajorVersion.isBlank() ? Map.of() : Map.of("API-Version", apiMajorVersion);
    }

    @NonNull
    private Map<String, String> partnerQuery(@NonNull String partnerId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("partnerId", partnerId);
        return query;
    }

    @NonNull
    private <T> T read(@Nullable String body, @NonNull Class<T> type, @NonNull String what) {
        if (body == null || body.isBlank()) {
            throw new WorkorderAuthDecodeException(what + " response body was empty");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (RuntimeException e) {
            throw new WorkorderAuthDecodeException(what + " response was not readable JSON: " + e.getMessage(), e);
        }
    }

    @NonNull
    private <T> List<T> readList(@Nullable String body, @NonNull TypeReference<List<T>> type, @NonNull String what) {
        if (body == null || body.isBlank()) {
            throw new WorkorderAuthDecodeException(what + " response body was empty");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (RuntimeException e) {
            throw new WorkorderAuthDecodeException(what + " response was not readable JSON: " + e.getMessage(), e);
        }
    }

    @Nullable
    private static String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    private static String firstErrorDetail(WorkorderS2SWire.@NonNull WorkorderDecision decision) {
        if (decision.errors() == null || decision.errors().isEmpty()) {
            return null;
        }
        return decision.errors().getFirst().detail();
    }

    @Nullable
    private static String firstErrorCode(WorkorderS2SWire.@NonNull WorkorderDecision decision) {
        if (decision.errors() == null || decision.errors().isEmpty()) {
            return null;
        }
        return decision.errors().getFirst().code();
    }

    /**
     * Reads a vendor decimal.
     *
     * <p>Comma decimal separators are normalised, as in every sibling codec: a vendor writing
     * {@code 1.234,56} in a locale that does so must not be read as one thousand two hundred.
     */
    @Nullable
    private static BigDecimal decimalOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            // An unreadable ceiling is not a zero ceiling. Dropping it leaves the authorization
            // without a stated limit, which is what "we could not read it" actually means.
            return null;
        }
    }

    @Nullable
    private static LocalDate dateOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim().length() > 10 ? value.trim().substring(0, 10) : value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Nullable
    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Pulls {@code references.value} strings out of an EDIWheel black/white list entry array. */
    @NonNull
    @SuppressWarnings("unchecked")
    private static List<String> references(Object listNode) {
        if (!(listNode instanceof List<?> entries)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> entryMap
                    && entryMap.get("references") instanceof Map<?, ?> reference
                    && reference.get("value") != null) {
                values.add(String.valueOf(reference.get("value")));
            }
        }
        return List.copyOf(values);
    }

    /** Percent-encodes a path segment so a vendor identifier cannot alter the path it sits in. */
    @NonNull
    private static String encodePathSegment(@NonNull String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
