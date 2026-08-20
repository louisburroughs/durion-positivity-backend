package com.positivity.inventory.internal.enums;

/**
 * How a physical count's quantity was obtained (ADR-0055 stage 4, #1416, issue #1414 owner spec).
 *
 * <p>Discrete stock is walked and tallied by hand — {@link #MANUAL_COUNT}, the default, and the
 * only method that has ever applied here. Bulk stock (tanks, drums, bins of loose material) is
 * measured instead: a gauge or dip reading, a scale, a flow meter, or a level sensor. The method
 * is captured verbatim rather than inferred, because it is part of the audit trail the owner's
 * spec requires ("Required Data: ... Measurement method") — a reviewer investigating an
 * out-of-tolerance variance needs to know whether the number came off a calibrated meter or a
 * stick dipped in a tank.
 */
public enum MeasurementMethod {

    /** Physically counted or tallied by hand. Default; unchanged from pre-ADR-0055 behavior. */
    MANUAL_COUNT,

    /** Read from a dip stick or gauge stick inserted into the tank. */
    DIP,

    /** Read from a fixed or portable gauge (sight glass, float gauge, pressure gauge). */
    GAUGE,

    /** Weighed on a scale; the reading is a mass converted to the inventory UoM. */
    SCALE,

    /** Read from a flow meter, typically at dispense or fill. */
    METER,

    /** Read from a fixed level or volume sensor (ultrasonic, capacitive, radar). */
    SENSOR
}
