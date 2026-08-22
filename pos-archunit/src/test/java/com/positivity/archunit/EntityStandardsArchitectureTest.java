package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.UUID;

/**
 * Enforces ADR-0013 and ADR-0024 entity standards.
 *
 * Rollout is module-by-module; add packages to ENFORCED_ENTITY_PACKAGES only
 * after that module has been migrated.
 */
@AnalyzeClasses(packages = "com.positivity", importOptions = ImportOption.DoNotIncludeTests.class)
class EntityStandardsArchitectureTest {

    // Every package listed here must contain at least one @Entity visible to the importer —
    // ClasspathVisibilityGuardTest enforces this so the rules below can never pass vacuously (#909).
    // pos-documents has no JPA entities, so it is intentionally absent.
    static final String[] ENFORCED_ENTITY_PACKAGES = {
        "com.positivity.location.internal.entity..",
        "com.positivity.inventory.internal.entity..",
        "com.positivity.accounting.internal.entity..",
        "com.positivity.invoice.internal.entity..",
        "com.positivity.order.internal.entity..",
        "com.positivity.people.internal.entity..",
        "com.positivity.peoplecontact.internal.entity..",
        "com.positivity.supplier.internal.entity..",
        "com.positivity.warranty.internal.entity.."
    };

    private static final String ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String ID_ANNOTATION = "jakarta.persistence.Id";
    private static final String ENTITY_LISTENERS_ANNOTATION = "jakarta.persistence.EntityListeners";
    private static final String CREATED_DATE_ANNOTATION = "org.springframework.data.annotation.CreatedDate";
    private static final String LAST_MODIFIED_DATE_ANNOTATION = "org.springframework.data.annotation.LastModifiedDate";
    private static final String UUIDV7_ID_ANNOTATION = "com.positivity.shared.id.UUIDv7Id";
    private static final String UUIDV7_GENERATOR = "com.positivity.shared.id.UUIDv7Generator";
    private static final String ASSIGNED_IDENTIFIER_ANNOTATION = "com.positivity.shared.id.AssignedIdentifier";
    private static final String LOB_ANNOTATION = "jakarta.persistence.Lob";

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {

                @Override
                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

    private static final DescribedPredicate<JavaClass> HAS_AUDIT_FIELDS =
            new DescribedPredicate<>("have createdAt or updatedAt field") {
                @Override
                public boolean test(JavaClass input) {
                    return input.getAllFields().stream()
                            .map(HasName::getName)
                            .anyMatch(name -> "createdAt".equals(name) || "updatedAt".equals(name));
                }
            };

    @ArchTest
    static final ArchRule entities_should_not_call_uuid_random_uuid = noClasses()
            .that()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .should()
            .callMethodWhere(UUID_RANDOM_UUID_CALL)
            .allowEmptyShould(true)
            .because("ADR-0013 disallows UUID.randomUUID() in entities");

    /**
     * ADR-0013 governs identifier <em>generation</em>, so a natural key assigned by the caller is outside it:
     * requiring {@code @UUIDv7Id} there asks a generator to invent a value whose whole meaning is that it must
     * not be invented. Such a field opts out with {@code @AssignedIdentifier} instead, which documents the
     * intent at the entity rather than leaving a generator annotation that contradicts the field (#1261).
     */
    @ArchTest
    static final ArchRule uuid_id_fields_should_use_adr_0013_generation = classes()
            .that()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .should(useAdr0013IdentifierStandard())
            .allowEmptyShould(true)
            .because("ADR-0013 requires generated UUID IDs to use @UUIDv7Id or UUIDv7Generator,"
                    + " and assigned natural keys to say so with @AssignedIdentifier");

    /**
     * {@code @AssignedIdentifier} is an opt-out from the identifier rule and means nothing anywhere else.
     * Left to drift onto ordinary columns it reads as significant while doing nothing.
     *
     * <p>Fields are the whole surface: the annotation targets {@code FIELD} only, so a method can never carry
     * it and no companion rule over methods is needed.
     */
    @ArchTest
    static final ArchRule assigned_identifier_should_only_mark_id_fields = fields().that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areAnnotatedWith(ASSIGNED_IDENTIFIER_ANNOTATION)
            .should()
            .beAnnotatedWith(ID_ANNOTATION)
            .allowEmptyShould(true)
            .because("@AssignedIdentifier only exempts an @Id from ADR-0013; on any other field it is decoration");

    @ArchTest
    static final ArchRule created_at_field_should_use_created_date = fields().that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .and()
            .haveName("createdAt")
            .should()
            .beAnnotatedWith(CREATED_DATE_ANNOTATION)
            .allowEmptyShould(true)
            .because("ADR-0024 requires createdAt to be populated via @CreatedDate");

    @ArchTest
    static final ArchRule updated_at_field_should_use_last_modified_date = fields().that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .and()
            .haveName("updatedAt")
            .should()
            .beAnnotatedWith(LAST_MODIFIED_DATE_ANNOTATION)
            .allowEmptyShould(true)
            .because("ADR-0024 requires updatedAt to be populated via @LastModifiedDate");

    /**
     * On Postgres, {@code @Lob} on a {@code String} does not mean "unbounded text" — it stores a
     * large-object OID whose bytes live in {@code pg_largeobject}. Hibernate then reads the value
     * during entity hydration through the LOB API, which requires an ambient transaction, so any
     * load of the entity in auto-commit mode fails — and rewriting or deleting the row leaks the
     * object, since large objects are never unlinked automatically (#1461). Long text belongs in a
     * plain {@code text} column: {@code @Column(columnDefinition = "TEXT")}. This rule is
     * fleet-wide, not limited to ENFORCED_ENTITY_PACKAGES, because the failure mode is identical
     * everywhere.
     */
    @ArchTest
    static final ArchRule string_fields_should_not_be_lob = noFields()
            .that()
            .haveRawType(String.class)
            .should()
            .beAnnotatedWith(LOB_ANNOTATION)
            .allowEmptyShould(true)
            .because("@Lob on String maps to a Postgres large object (oid): reads fail outside a transaction"
                    + " and updates/deletes leak pg_largeobject storage (#1461);"
                    + " use @Column(columnDefinition = \"TEXT\") instead");

    @ArchTest
    static final ArchRule entities_with_audit_fields_should_declare_entity_listeners = classes()
            .that()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .and(HAS_AUDIT_FIELDS)
            .should()
            .beAnnotatedWith(ENTITY_LISTENERS_ANNOTATION)
            .allowEmptyShould(true)
            .because("ADR-0024 requires entity listener-based auditing for createdAt/updatedAt");

    private static ArchCondition<JavaClass> useAdr0013IdentifierStandard() {
        return new ArchCondition<>("declare UUID @Id fields with @UUIDv7Id or UUIDv7Generator") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                List<JavaField> idFields = item.getAllFields().stream()
                        .filter(field -> field.isAnnotatedWith(ID_ANNOTATION))
                        .toList();
                if (idFields.isEmpty()) {
                    return;
                }

                boolean dependsOnUuidV7Generator = item.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dep ->
                                UUIDV7_GENERATOR.equals(dep.getTargetClass().getName()));

                for (JavaField idField : idFields) {
                    if (!idField.getRawType().isEquivalentTo(UUID.class)) {
                        continue; // legacy non-UUID identifiers are handled in module
                        // migrations
                    }
                    boolean hasUuidV7IdAnnotation = idField.isAnnotatedWith(UUIDV7_ID_ANNOTATION);
                    boolean isAssignedIdentifier = idField.isAnnotatedWith(ASSIGNED_IDENTIFIER_ANNOTATION);

                    if (isAssignedIdentifier && hasUuidV7IdAnnotation) {
                        // The two make opposite claims: one says the platform mints this id, the other says
                        // only the caller may supply it. Carrying both leaves the generator attached, which is
                        // exactly the state @AssignedIdentifier exists to replace.
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " declares both @UUIDv7Id and @AssignedIdentifier on @Id field '"
                                        + idField.getName()
                                        + "'; an identifier is either generated or assigned, not both"));
                        continue;
                    }
                    if (isAssignedIdentifier) {
                        assertReasonIsGiven(item, idField, events);
                        continue; // a natural key is not generated, so ADR-0013 does not govern it
                    }
                    if (!hasUuidV7IdAnnotation && !dependsOnUuidV7Generator) {
                        String message = item.getName()
                                + " has UUID @Id field '" + idField.getName()
                                + "' but uses neither @UUIDv7Id nor UUIDv7Generator"
                                + " (if it is a natural key assigned by the caller, declare @AssignedIdentifier)";
                        events.add(SimpleConditionEvent.violated(item, message));
                    }
                }
            }
        };
    }

    /**
     * An opt-out from a fleet-wide rule with no stated reason is indistinguishable from a mistake, so the
     * marker's reason is required to say something.
     */
    private static void assertReasonIsGiven(JavaClass item, JavaField idField, ConditionEvents events) {
        String reason = String.valueOf(idField.getAnnotationOfType(ASSIGNED_IDENTIFIER_ANNOTATION)
                .get("value")
                .orElse(""));
        if (reason.isBlank()) {
            events.add(SimpleConditionEvent.violated(
                    item,
                    item.getName() + " marks @Id field '" + idField.getName()
                            + "' @AssignedIdentifier without saying why it is assigned rather than generated"));
        }
    }
}
