package com.positivity.supplier.internal.adapter.michelins2s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Michelin S2S wire records, modelled literally (ADR-0051 §2).
 *
 * <h2>Fields marked GUESS are guesses</h2>
 *
 * The source spec types the workorder request body as {@code JsonNode} and gives no response schema
 * for the workorder operations, so the request shape below is derived from the EDIWheel
 * party/vehicle/reference vocabulary the same spec <em>does</em> define for its lookups. Each such
 * field carries a {@code GUESS} note.
 *
 * <p>They are marked rather than merely written because the alternative is a first sandbox run that
 * fails with "400 Bad Request" and no way to tell an invented field from a mistyped one. The lookup
 * records below are not guesses — those shapes are specified.
 *
 * <p>Package-private by design: no vendor type reaches the domain, an event, or an API contract.
 */
final class WorkorderS2SWire {

    private WorkorderS2SWire() {}

    /**
     * The authorization request body.
     *
     * <p>GUESS — the spec declares this as {@code JsonNode}. Modelled on the EDIWheel vocabulary the
     * lookups use, so that when a sandbox corrects it the correction is likely to be renaming or
     * nesting rather than starting over.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WorkorderRequest(
            /* GUESS: our own reference for the job, echoed back on the decision. */
            @Nullable String externalReference,
            /* GUESS: how the fleet vehicle is identified — the lookups accept an ident of this shape. */
            @Nullable String vehicleId,
            @Nullable String licensePlate,
            @Nullable String vin,
            @Nullable String fleetNumber,
            /* GUESS: the contract the work is claimed under. */
            @Nullable String contractId,
            @Nullable String odometerValue,
            /* GUESS: line items the authorization is requested for. */
            @Nullable List<WorkorderRequestLine> lines) {}

    /** GUESS — one requested operation or part on the authorization request. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WorkorderRequestLine(
            @Nullable String reference,
            @Nullable String description,
            @Nullable Integer quantity,
            @Nullable String tirePosition) {}

    /**
     * The authorization decision, however it arrives — in a 201 body, or from polling a 202's
     * {@code Location}.
     *
     * <p>GUESS on field names. Several alternatives are accepted at decode time rather than one
     * being picked, because guessing wrong here silently yields "granted with no id", which cannot
     * be approved on completion and is not noticed until months later.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkorderDecision(
            @Nullable String id,
            @Nullable String workOrderId,
            @Nullable String status,
            @Nullable String state,
            @Nullable String contractId,
            @Nullable String contractReference,
            @Nullable String authorizedAmount,
            @Nullable String currency,
            @Nullable String reasonCode,
            @Nullable String reason,
            @Nullable String message,
            @Nullable List<ApiErrorDetail> errors) {}

    /** {@code ApiErrorDetail} — specified by the source spec. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiErrorDetail(
            @Nullable String id,
            @Nullable String code,
            @Nullable String detail) {}

    /** {@code EDIWheelVehicle} — specified by the source spec. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Vehicle(
            @Nullable String id,
            @Nullable String licensePlate,
            @Nullable String fleetNumber,
            @Nullable String vin,
            @Nullable String registrationCountry,
            @Nullable String brand,
            @Nullable String model,
            @Nullable Integer year,
            @Nullable String type,
            @Nullable String odometerValue) {}

    /** {@code EDIWheelVehicleDetails} — a contract with the vehicles it covers. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContractDetails(
            @Nullable String id,
            @Nullable String name,
            @Nullable Contractor contractor,
            @Nullable String startDate,
            @Nullable String endDate,
            @Nullable List<Vehicle> vehicles) {}

    /** {@code EDIWheelContractor} — specified by the source spec. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Contractor(@Nullable String id, @Nullable String name) {}
}
