package com.positivity.accounting.internal.exception;

/**
 * A consumed {@code order.session.closed} fact parses but breaks the close fact's contract (CAP:550 S17, S18; #2594):
 * a petty expense or a bank drop with no movement id, a movement id named twice, not {@code OUT}, no category, no
 * positive amount, or a schema-2 fact without its {@code movements} list. Redelivery cannot fix it: the fact is the
 * producer's.
 *
 * <p>The session posts nothing (all or nothing), and the listener records the fact {@code FAILED / VALIDATION_ERROR}
 * with this message instead of sending it to retry and the DLQ (ADR-0044, permanent-failure rule of 2026-10-02). An
 * {@link IllegalArgumentException}, so a caller that only knew the contract checks threw that still catches it; a
 * dedicated type, so an {@code IllegalArgumentException} from anywhere else (a JPA or Hibernate call) is never
 * mistaken for the fact's contract.
 */
public class RegisterSessionContractViolationException extends IllegalArgumentException {

    public RegisterSessionContractViolationException(String message) {
        super(message);
    }
}
