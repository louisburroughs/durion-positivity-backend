package com.positivity.shared.id;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a JPA {@code @Id} that is a <strong>natural key assigned by the caller</strong> rather than a
 * surrogate key the platform mints.
 *
 * <h2>Why this exists</h2>
 *
 * ADR-0013 governs identifier <em>generation</em>: every UUID identifier the platform invents must be a
 * UUID v7, produced by {@link UUIDv7Id} or {@link UUIDv7Generator}. The fleet-wide rule that enforces it
 * ({@code EntityStandardsArchitectureTest}) reads "every UUID {@code @Id} declares {@code @UUIDv7Id}",
 * which is correct for surrogate keys and wrong for an identifier whose whole meaning is that it must not
 * be invented — annotating one asks a generator to make up a value that is only valid if it comes from
 * somewhere else.
 *
 * <p>Carrying {@code @UUIDv7Id} on such a field is not merely redundant. {@link UUIDv7HibernateGenerator}
 * honours an assigned value ({@code currentValue != null ? currentValue : generate()}), so a supplied id
 * survives — but a <em>missing</em> one is silently minted instead of failing, turning a loud
 * {@code NOT NULL} error into a row keyed to an entity that does not exist. Declaring this annotation
 * instead leaves the field a plain assigned identifier, so Hibernate rejects a null id at persist time.
 *
 * <h2>Using it</h2>
 *
 * Declare it <em>instead of</em> {@link UUIDv7Id}, never alongside it — the two make opposite claims, and
 * the architecture rule fails a field carrying both:
 *
 * <pre>{@code
 * @Id
 * @AssignedIdentifier("the binding's own id; a lease on an invented id would be a lease on nothing")
 * @Column(name = "binding_id", nullable = false, updatable = false)
 * private UUID bindingId;
 * }</pre>
 *
 * <p>This is a documentation marker: it attaches no Hibernate generator and changes no runtime behaviour.
 * Its only mechanical effect is to exempt the field from the {@code @UUIDv7Id} requirement, and the
 * reason it carries is what the next reader of the entity sees in place of a generator annotation that
 * contradicts the field's purpose.
 *
 * <p>It does not apply to surrogate keys. If the identifier is one the platform is free to invent, it
 * belongs under ADR-0013 and must use {@link UUIDv7Id}.
 */
@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface AssignedIdentifier {

    /**
     * Why this identifier is assigned rather than generated — what supplies the value, and what a minted
     * one would mean. Required, and required to be non-blank: an unexplained opt-out from a fleet-wide
     * rule is indistinguishable from a mistake, so the architecture rule rejects an empty reason.
     */
    String value();
}
