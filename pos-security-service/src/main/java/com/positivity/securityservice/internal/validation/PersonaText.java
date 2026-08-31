package com.positivity.securityservice.internal.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Structural containment for an MCP role persona slot (#1613, D9 control 1).
 *
 * <p>Persona slots are interpolated into a fixed template in {@code pos-mcp-server} and assembled
 * into the ROLE layer, which sits <em>above</em> the TOOL_USE and WRITE_GATE layers. Unconstrained
 * text in a slot could therefore sit adjacent to the write-gate contract and contradict it, for
 * every user of that role, silently. This constraint keeps a slot descriptive: the persona can fill
 * a slot in the prompt, never be the prompt.
 *
 * <p>Null and blank are valid — they mean "derive this slot" (D5), not "reject the role".
 *
 * @see PersonaTextValidator
 */
@Documented
@Constraint(validatedBy = PersonaTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonaText {

    String message() default "is not a valid persona slot";

    /** Maximum length in characters. Mirrors the column width in {@code V34}. */
    int max();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
