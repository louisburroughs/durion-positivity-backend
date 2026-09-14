package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * A technician this module has never heard of was named on an assignment (#1983).
 *
 * <p>Checked against the {@code ext_person} replica pos-people feeds on {@code people.events.v1},
 * never by a synchronous call into that service (ADR-0044 §6) — the same arrangement that lets a bay
 * be validated against {@code ext_bay}.
 *
 * <p>422, not 404: the technician is not the resource the URL addresses, and the payload is
 * well-formed with every field inside its declared type. What fails is a cross-entity rule — this
 * id names nobody — which ADR-0017 §2 places at 422, matching
 * {@link ServicePositionInvalidException} for the position half of the same operation.
 */
public class TechnicianNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "TECHNICIAN_NOT_FOUND";

    public TechnicianNotFoundException(UUID technicianId) {
        super("Unknown technician " + technicianId);
    }
}
