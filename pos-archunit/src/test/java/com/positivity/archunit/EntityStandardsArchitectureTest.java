package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

    private static final String[] ENFORCED_ENTITY_PACKAGES = {
        "com.positivity.location.internal.entity..",
        "com.positivity.documents.internal.entity..",
        "com.positivity.inventory.internal.entity..",
        "com.positivity.accounting.internal.entity..",
        "com.positivity.invoice.internal.entity..",
        "com.positivity.order.internal.entity..",
        "com.positivity.people.internal.entity..",
        "com.positivity.peoplecontact.internal.entity.."
    };

    private static final String ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String ID_ANNOTATION = "jakarta.persistence.Id";
    private static final String ENTITY_LISTENERS_ANNOTATION = "jakarta.persistence.EntityListeners";
    private static final String CREATED_DATE_ANNOTATION = "org.springframework.data.annotation.CreatedDate";
    private static final String LAST_MODIFIED_DATE_ANNOTATION = "org.springframework.data.annotation.LastModifiedDate";
    private static final String UUIDV7_ID_ANNOTATION = "com.positivity.shared.id.UUIDv7Id";
    private static final String UUIDV7_GENERATOR = "com.positivity.shared.id.UUIDv7Generator";

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

    @ArchTest
    static final ArchRule uuid_id_fields_should_use_adr_0013_generation = classes()
            .that()
            .resideInAnyPackage(ENFORCED_ENTITY_PACKAGES)
            .and()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .should(useAdr0013IdentifierStandard())
            .allowEmptyShould(true)
            .because("ADR-0013 requires UUID IDs to use @UUIDv7Id or UUIDv7Generator");

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
                    if (!hasUuidV7IdAnnotation && !dependsOnUuidV7Generator) {
                        String message = item.getName()
                                + " has UUID @Id field '" + idField.getName()
                                + "' but uses neither @UUIDv7Id nor UUIDv7Generator";
                        events.add(SimpleConditionEvent.violated(item, message));
                    }
                }
            }
        };
    }
}
